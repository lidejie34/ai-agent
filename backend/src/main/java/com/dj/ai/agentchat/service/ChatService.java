package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.ChatNotConfiguredException;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryPersistException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.memory.ConversationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话服务（厚层）：请求校验、多轮消息组装、ChatClient 同步/流式调用、异常归一。
 *
 * <p>迭代3 起支持会话持久化（sessionId 三态，FR-1）：
 * <ul>
 *   <li>缺省/null = 无状态：零 DB 交互，行为与迭代1/2 完全一致；</li>
 *   <li>显式空串 = 首轮新建：服务端生成 UUID、INSERT IGNORE 建会话（兼作 DB 探测）、
 *       seed history 回填，模型成功后 seed+user+assistant 同批落库；</li>
 *   <li>36 位 UUID = 续接：非法格式 400 不触达 DB；幂等建会话 + 加载最近 N 条历史组装，
 *       请求体 history 仅 warn 忽略，成功后只落本轮 user+assistant。</li>
 * </ul>
 * 记忆阶段（模型调用前）DB/建表/连接失败 → {@link MemoryUnavailableException}（503/订阅前 error）；
 * 同步落库失败 → {@link MemoryPersistException}（500）；模型失败不落库（502）。
 * 不挂载 MessageChatMemoryAdvisor，加载/落库时机由本类显式编排（实证 2）。
 */
@Slf4j
@Service
public class ChatService {

    /** application.yml 中 api-key 的默认占位值（M7 自动配置要求 bean 创建期 key 非空白）。 */
    static final String UNCONFIGURED_KEY_PLACEHOLDER = "ark-placeholder-not-configured";

    static final String NOT_CONFIGURED_MESSAGE =
            "未检测到 ARK_API_KEY，请通过环境变量或 application-local.yml 配置后重启。";
    static final String MODEL_FAILED_MESSAGE = "模型调用失败，请稍后重试或检查 API Key 与模型配置。";
    static final String STREAM_FAILED_MESSAGE = "模型流式调用失败，请稍后重试。";
    static final String MEMORY_UNAVAILABLE_MESSAGE = "会话服务暂不可用，请稍后重试";
    static final String MEMORY_PERSIST_FAILED_MESSAGE = "会话保存失败，请重试本轮对话";
    /** 记忆功能未启用提示：会话 REST 接口（SessionService）与聊天路径共用，故提升为 public。 */
    public static final String MEMORY_DISABLED_MESSAGE = "会话记忆功能未启用";
    static final String ILLEGAL_SESSION_ID_MESSAGE = "sessionId 必须为服务端签发的 36 位会话 ID";

    private final ChatClient chatClient;
    private final String apiKey;
    private final String configuredModel;
    /** 流式首片段前重试：最大尝试次数（含首次，与同步 RetryTemplate 一致为 3）。 */
    private final int streamRetryMaxAttempts;
    private final Duration streamRetryMinBackoff;
    private final Duration streamRetryMaxBackoff;
    /** 记忆存储（enabled=false 或无 bean 时为 null；无状态路径不访问）。 */
    private final ObjectProvider<ConversationStore> storeProvider;
    /** 续接加载最近 N 条历史（FR-7，默认 20）。 */
    private final int memoryMaxHistory;
    private final boolean memoryEnabled;

    @Autowired
    public ChatService(ChatClient chatClient,
                       @Value("${spring.ai.openai.api-key:}") String apiKey,
                       @Value("${spring.ai.openai.chat.options.model:}") String configuredModel,
                       @Value("${app.chat.retry.stream.max-attempts:3}") int streamRetryMaxAttempts,
                       @Value("${app.chat.retry.stream.min-backoff:1s}") Duration streamRetryMinBackoff,
                       @Value("${app.chat.retry.stream.max-backoff:10s}") Duration streamRetryMaxBackoff,
                       ObjectProvider<ConversationStore> conversationStoreProvider,
                       @Value("${app.chat.memory.max-history:20}") int memoryMaxHistory,
                       @Value("${app.chat.memory.enabled:true}") boolean memoryEnabled) {
        this.chatClient = chatClient;
        this.apiKey = apiKey;
        this.configuredModel = configuredModel;
        this.streamRetryMaxAttempts = streamRetryMaxAttempts;
        this.streamRetryMinBackoff = streamRetryMinBackoff;
        this.streamRetryMaxBackoff = streamRetryMaxBackoff;
        this.storeProvider = conversationStoreProvider;
        this.memoryMaxHistory = memoryMaxHistory;
        this.memoryEnabled = memoryEnabled;
    }

    /**
     * 测试/便捷构造（与迭代 1 签名兼容）：流式重试退避取毫秒级，离线测试快速确定、不真实等待；
     * 不带记忆存储（无状态行为；会话路径按「记忆未启用」400）。
     */
    public ChatService(ChatClient chatClient, String apiKey, String configuredModel) {
        this(chatClient, apiKey, configuredModel, 3,
                Duration.ofMillis(10), Duration.ofMillis(100), null, 20, true);
    }

    /**
     * 迭代3 测试便捷构造：注入（mock 的）记忆存储与历史窗口，记忆开关开启。
     */
    public ChatService(ChatClient chatClient, String apiKey, String configuredModel,
                       ConversationStore store, int memoryMaxHistory) {
        this(chatClient, apiKey, configuredModel, 3,
                Duration.ofMillis(10), Duration.ofMillis(100),
                new FixedObjectProvider<>(store), memoryMaxHistory, true);
    }

    /**
     * 迭代3 测试便捷构造：可显式关闭记忆开关（FR-17）。
     */
    public ChatService(ChatClient chatClient, String apiKey, String configuredModel,
                       ConversationStore store, int memoryMaxHistory, boolean memoryEnabled) {
        this(chatClient, apiKey, configuredModel, 3,
                Duration.ofMillis(10), Duration.ofMillis(100),
                new FixedObjectProvider<>(store), memoryMaxHistory, memoryEnabled);
    }

    /**
     * 同步问答：等待完整回复后一次性返回。
     */
    public ChatResponse chat(ChatRequest request) {
        validate(request);
        ensureConfigured();
        MemoryContext memory = prepareMemory(request);
        List<Message> messages = assembleMessages(request, memory);
        log.debug("同步调用模型: 消息总数={}, 配置model={}, sessionId={}",
                messages.size(), configuredModel, memory.sessionId());
        org.springframework.ai.chat.model.ChatResponse aiResponse;
        try {
            aiResponse = chatClient.prompt().messages(messages).call().chatResponse();
        } catch (InvalidChatRequestException | ChatNotConfiguredException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("同步模型调用异常: sessionId={}, {}", memory.sessionId(), e.getMessage());
            throw new ModelCallException(MODEL_FAILED_MESSAGE, e);
        }
        String reply = extractReply(aiResponse);
        String model = resolveModel(aiResponse);
        // 模型成功后成对落库（失败 → 500，reply 不返回）；模型异常不落库
        if (memory.stateful()) {
            persistTurn(memory, request.message(), reply);
        }
        return new ChatResponse(reply, model, memory.sessionId());
    }

    /**
     * 流式问答：返回 {@link ChatStreamResult}（会话 ID + 逐段文本 Flux，由控制器订阅推送 SSE）。
     *
     * <p>校验/缺 Key/记忆阶段（建会话/加载历史/DB 探测）全部在<b>装配并返回 Flux 前同步执行</b>：
     * 记忆失败在此直接抛出（控制器订阅前 catch → error 事件，不发 session、不起心跳，AC-15）。
     * 模型侧异常经 onErrorMap 归一为 {@link ModelCallException}；聚合全文在
     * {@code publishOn(boundedElastic)} 后的 doOnComplete 成对落库——落库在完成信号前执行
     * （done 晚于落库，FR-10），且内部 try/catch 吞掉异常（绝不让 complete 变 error，FR-12/AC-17）；
     * error/cancel/timeout/重试耗尽不触发 doOnComplete → 不落库、不留孤儿 user（FR-11）。
     * 聚合 StringBuilder 挂在每次调用内（Flux.defer 重订阅不共享、不重复落库，实证 10）。
     */
    public ChatStreamResult chatStream(ChatRequest request) {
        validate(request);
        ensureConfigured();
        MemoryContext memory = prepareMemory(request);
        List<Message> messages = assembleMessages(request, memory);
        log.debug("流式调用模型: 消息总数={}, 配置model={}, sessionId={}",
                messages.size(), configuredModel, memory.sessionId());
        // 边界说明（M7 实证）：
        // 1) Spring AI 的 RetryTemplate 只覆盖同步 internalCall，流式 internalStream/stream
        //    不引用 RetryTemplate；此处仅在应用层对「建连/首片段前」的瞬时故障（429/5xx/网络 IO）
        //    有限重试；首片段发出后流中断不重放（避免内容重复），直接下传 error 事件由控制器结束流；
        //    卡死靠心跳发现 + Netty responseTimeout 兜底。
        // 2) M7 DefaultAroundAdvisorChain 的 Advisor 队列有状态（逐次 pop），同一 Flux 重订阅会
        //    抛 "No AroundAdvisor available to execute"，故整个 ChatClient 流式装配必须包在
        //    Flux.defer 中：每次（重）订阅都重建 Advisor 链并重新调用模型（重试才真正生效）。
        StringBuilder aggregated = new StringBuilder();
        Flux<String> deferred = Flux.defer(
                () -> chatClient.prompt().messages(messages).stream().content());
        Flux<String> chunks = withFirstChunkRetry(deferred)
                .onErrorMap(RuntimeException.class,
                        e -> (e instanceof ModelCallException) ? e : new ModelCallException(STREAM_FAILED_MESSAGE, e))
                .doOnNext(aggregated::append)
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnComplete(() -> persistStreamTurn(memory, request.message(), aggregated));
        return new ChatStreamResult(memory.stateful() ? memory.sessionId() : null, chunks);
    }

    /**
     * 流式 complete 后成对落库：[落库前缀(seed)] + 本轮 user + 聚合全文 assistant。
     * 任何异常仅 error 日志（吞掉）——若抛出会被 Reactor 转成 onError 导致 done 变 error（AC-17）。
     */
    private void persistStreamTurn(MemoryContext memory, String userText, StringBuilder aggregated) {
        if (!memory.stateful()) {
            return;
        }
        List<Message> toPersist = new ArrayList<>(memory.persistPrefix());
        toPersist.add(new UserMessage(userText));
        toPersist.add(new AssistantMessage(aggregated.toString()));
        try {
            memory.store().add(memory.sessionId(), toPersist);
        } catch (RuntimeException e) {
            log.error("流式会话落库失败（不影响已推送片段与 done）: sessionId={}, 聚合回复长度={}",
                    memory.sessionId(), aggregated.length(), e);
        }
    }

    /**
     * 首片段前有限重试：{@code emitted} 标记是否已向调用方发出过片段；
     * 谓词严格限定「未发出任何片段 且 异常为 429/5xx（WebClientResponseException）或网络/IO 类」。
     */
    private Flux<String> withFirstChunkRetry(Flux<String> content) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        long retryCount = Math.max(0, streamRetryMaxAttempts - 1L);
        return content
                .doOnNext(chunk -> emitted.set(true))
                .retryWhen(Retry.backoff(retryCount, streamRetryMinBackoff)
                        .maxBackoff(streamRetryMaxBackoff)
                        .filter(error -> isRetryableStreamError(error, emitted)));
    }

    private static boolean isRetryableStreamError(Throwable error, AtomicBoolean emitted) {
        if (emitted.get()) {
            return false;
        }
        if (error instanceof WebClientResponseException clientError) {
            int status = clientError.getStatusCode().value();
            // 429 限流与 5xx 服务端错误可重试；其余 4xx（400/401/404…）立即失败，与同步分类一致
            return status == 429 || clientError.getStatusCode().is5xxServerError();
        }
        // 网络层故障（连接拒绝/读超时/连接重置）：沿 cause 链识别 IO/资源类异常
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof ResourceAccessException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    // ---- 记忆编排（迭代3） ----

    /**
     * 记忆阶段（模型调用前同步执行；流式同样在装配/订阅 Flux 前调用）。
     * 返回的上下文携带：会话 ID（null=无状态）、存储引用、Prompt 前缀消息、落库前缀消息。
     */
    private MemoryContext prepareMemory(ChatRequest request) {
        List<Message> requestHistory = toMessages(request.history());
        String sid = request.sessionId();
        if (sid == null) {
            // 无状态：后续零 store 交互（AC-1）
            return new MemoryContext(null, null, requestHistory, List.of());
        }
        ConversationStore store = currentStore();
        if (store == null || !memoryEnabled) {
            // FR-17：记忆关闭时会话路径 400
            throw new InvalidChatRequestException(MEMORY_DISABLED_MESSAGE);
        }
        if (!StringUtils.hasText(sid)) {
            // 首轮新建：服务端生成 UUID（36 位），INSERT IGNORE 建会话兼作 DB 探测
            String newSid = UUID.randomUUID().toString();
            List<Message> seed = requestHistory;
            try {
                store.createSessionIfAbsent(newSid);
            } catch (DataAccessException e) {
                log.warn("新建会话失败（记忆服务不可用）: {}", e.getMessage());
                throw new MemoryUnavailableException(MEMORY_UNAVAILABLE_MESSAGE, e);
            }
            // seed 既进 Prompt 也同批落库（seed-once，FR-8/AC-12）
            return new MemoryContext(newSid, store, seed, seed);
        }
        // 续接：先校验 UUID 格式（非法 400，不触达 DB，FR-2/AC-14）
        try {
            UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            throw new InvalidChatRequestException(ILLEGAL_SESSION_ID_MESSAGE);
        }
        if (!requestHistory.isEmpty()) {
            // 以库为准：请求体 history 仅 warn 忽略，不报错（FR-13 续接语义/AC-13）
            log.warn("续接会话忽略请求体 history: sessionId={}, 忽略条数={}", sid, requestHistory.size());
        }
        try {
            // 幂等自愈补会话行（未知格式合法 UUID）+ DB 探测；加载最近 N 条时间正序历史
            store.createSessionIfAbsent(sid);
            List<Message> prior = store.get(sid, memoryMaxHistory);
            // 历史只进 Prompt，不回写落库
            return new MemoryContext(sid, store, prior, List.of());
        } catch (DataAccessException e) {
            log.warn("加载会话历史失败（记忆服务不可用）: sessionId={}, {}", sid, e.getMessage());
            throw new MemoryUnavailableException(MEMORY_UNAVAILABLE_MESSAGE, e);
        }
    }

    /**
     * 同步成对落库：[落库前缀(seed)] + 本轮 user + assistant 同批写入；
     * DataAccessException → 500（reply 不返回，客户端整轮重试，FR-12/AC-17）。
     */
    private void persistTurn(MemoryContext memory, String userText, String reply) {
        List<Message> toPersist = new ArrayList<>(memory.persistPrefix());
        toPersist.add(new UserMessage(userText));
        toPersist.add(new AssistantMessage(reply));
        try {
            memory.store().add(memory.sessionId(), toPersist);
        } catch (DataAccessException e) {
            log.error("同步会话落库失败: sessionId={}, 消息条数={}",
                    memory.sessionId(), toPersist.size(), e);
            throw new MemoryPersistException(MEMORY_PERSIST_FAILED_MESSAGE, e);
        }
    }

    private ConversationStore currentStore() {
        return storeProvider == null ? null : storeProvider.getIfAvailable();
    }

    private List<Message> assembleMessages(ChatRequest request, MemoryContext memory) {
        List<Message> messages = new ArrayList<>(memory.promptPrefix());
        messages.add(new UserMessage(request.message()));
        return messages;
    }

    /**
     * 记忆上下文：{@code sessionId==null} 即无状态；{@code promptPrefix} 为模型调用前的历史
     * （无状态=请求体 history；新建=seed；续接=库中最近 N 条）；{@code persistPrefix} 为
     * 落库时随本轮 user/assistant 同批写入的前缀（仅新建态的 seed；续接不回写历史）。
     */
    private record MemoryContext(String sessionId,
                                 ConversationStore store,
                                 List<Message> promptPrefix,
                                 List<Message> persistPrefix) {

        boolean stateful() {
            return sessionId != null;
        }
    }

    // ---- 内部 ----

    private void validate(ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new InvalidChatRequestException("message 不能为空");
        }
        if (request.history() != null) {
            for (ChatMessage h : request.history()) {
                if (h == null) {
                    throw new InvalidChatRequestException("history 中存在空消息");
                }
                String role = h.role();
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    throw new InvalidChatRequestException(
                            "history 中 role 仅支持 user 或 assistant，收到: " + role);
                }
                if (!StringUtils.hasText(h.content())) {
                    throw new InvalidChatRequestException("history 中 content 不能为空");
                }
            }
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(apiKey) || UNCONFIGURED_KEY_PLACEHOLDER.equals(apiKey.trim())) {
            throw new ChatNotConfiguredException(NOT_CONFIGURED_MESSAGE);
        }
    }

    private static List<Message> toMessages(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(history.size());
        for (ChatMessage h : history) {
            if ("assistant".equals(h.role())) {
                messages.add(new AssistantMessage(h.content()));
            } else {
                messages.add(new UserMessage(h.content()));
            }
        }
        return messages;
    }

    private String extractReply(org.springframework.ai.chat.model.ChatResponse aiResponse) {
        if (aiResponse != null && aiResponse.getResult() != null
                && aiResponse.getResult().getOutput() != null
                && aiResponse.getResult().getOutput().getText() != null) {
            return aiResponse.getResult().getOutput().getText();
        }
        return "";
    }

    private String resolveModel(org.springframework.ai.chat.model.ChatResponse aiResponse) {
        String model = null;
        if (aiResponse != null && aiResponse.getMetadata() != null) {
            model = aiResponse.getMetadata().getModel();
        }
        return StringUtils.hasText(model) ? model : (configuredModel == null ? "" : configuredModel);
    }

    /**
     * 测试便捷用的固定 ObjectProvider：始终返回构造时给定的 bean（可为 null）。
     */
    static class FixedObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        FixedObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }

        @Override
        public Iterator<T> iterator() {
            return instance == null ? java.util.Collections.emptyIterator()
                    : List.of(instance).iterator();
        }
    }
}
