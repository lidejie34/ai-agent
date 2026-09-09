package com.dj.ai.agentchat.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求摘要日志 Advisor（迭代 2，自定义 Advisor 范式样板）：
 * 在<b>模型调用边界</b>记录一次调用的元信息——消息条数、模型标识、路径（call/stream）、
 * 耗时、成败、失败异常摘要、流式片段数。
 *
 * <p>安全红线（FR-1.2 / NFR-4）：info 级只打元信息，<b>不</b>打印消息正文、headers、
 * API Key / Authorization 任何内容；正文仅 debug 级打印长度（与迭代 1 控制器日志约定一致：
 * info=元信息，debug=内容）。日志逻辑全部 try/catch 自保，Advisor 自身异常绝不影响调用链。
 *
 * <p>形态照 M7 官方 {@code SimpleLoggerAdvisor} 范式：同时实现 CallAroundAdvisor 与
 * StreamAroundAdvisor；流式用 doOnXxx 副作用算子织入，不改变任何信号。
 * order 取 0（与 SimpleLoggerAdvisor 默认值一致）；Advisor 链按 order 升序嵌套，
 * 本 Advisor 始终包裹模型终调，耗时覆盖完整模型调用。
 */
@Slf4j
@Component
public class RequestLoggingAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    public static final String NAME = "request-logging-advisor";
    public static final int ORDER = 0;

    private final String configuredModel;

    public RequestLoggingAdvisor(
            @Value("${spring.ai.openai.chat.options.model:}") String configuredModel) {
        this.configuredModel = configuredModel == null ? "" : configuredModel;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ---------------- 同步路径 ----------------

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        long start = System.currentTimeMillis();
        int messageCount = messageCount(request);
        String model = resolveModel(request);
        safeLog(() -> log.info("模型调用开始[call]: 消息数={}, model={}", messageCount, model));
        safeLog(() -> log.debug("模型调用请求[call]: userText长度={}",
                request.userText() == null ? 0 : request.userText().length()));
        try {
            AdvisedResponse response = chain.nextAroundCall(request);
            long elapsed = System.currentTimeMillis() - start;
            safeLog(() -> log.info("模型调用成功[call]: 消息数={}, model={}, 耗时={}ms",
                    messageCount, model, elapsed));
            return response;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            // 只记录异常类名与 message 摘要（ResponseErrorHandler 的 message 含 HTTP 状态码，不含 Key）
            safeLog(() -> log.warn("模型调用失败[call]: 消息数={}, model={}, 耗时={}ms, 异常={}: {}",
                    messageCount, model, elapsed,
                    t.getClass().getSimpleName(), safeMessage(t)));
            throw t;
        }
    }

    // ---------------- 流式路径 ----------------

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        long start = System.currentTimeMillis();
        int messageCount = messageCount(request);
        String model = resolveModel(request);
        AtomicInteger chunkCount = new AtomicInteger(0);
        safeLog(() -> log.info("模型调用开始[stream]: 消息数={}, model={}", messageCount, model));

        return chain.nextAroundStream(request)
                .doOnNext(response -> chunkCount.incrementAndGet())
                .doOnComplete(() -> safeLog(() -> log.info(
                        "模型流式调用成功[stream]: 消息数={}, model={}, 片段数={}, 总耗时={}ms",
                        messageCount, model, chunkCount.get(), System.currentTimeMillis() - start)))
                .doOnError(t -> safeLog(() -> log.warn(
                        "模型流式调用失败[stream]: 消息数={}, model={}, 已发片段数={}, 总耗时={}ms, 异常={}: {}",
                        messageCount, model, chunkCount.get(), System.currentTimeMillis() - start,
                        t.getClass().getSimpleName(), safeMessage(t))));
        // 说明：doOnXxx 为副作用算子，不改变 Flux 信号；流中断/错误原样下传，由控制器发 error 事件。
    }

    // ---------------- 内部 ----------------

    /**
     * 统计本次调用实际发往模型的消息总数。
     *
     * <p>M7 字节码实证（{@code DefaultChatClient.toAdvisedRequest}）：{@code .messages(...)}
     * 传入列表的<b>最后一条 UserMessage 会被提升为 {@code userText} 并从 messages 移除</b>，
     * 故 Advisor 视角：{@code messages()} = 历史消息（不含本轮），{@code userText()} = 本轮用户消息，
     * {@code systemText()} = 系统提示（toPrompt 时渲染为 SystemMessage）。三者相加才是真实消息数。
     */
    private static int messageCount(AdvisedRequest request) {
        int count = request.messages() == null ? 0 : request.messages().size();
        if (request.userText() != null && !request.userText().isBlank()) {
            count++;
        }
        if (request.systemText() != null && !request.systemText().isBlank()) {
            count++;
        }
        return count;
    }

    private String resolveModel(AdvisedRequest request) {
        try {
            ChatOptions options = request.chatOptions();
            if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
                return options.getModel();
            }
        } catch (Exception ignored) {
            // 取模型标识失败不影响调用
        }
        return configuredModel;
    }

    /** 异常自保：日志逻辑本身失败只打 warn，绝不抛出影响调用链。 */
    private static void safeLog(Runnable logStatement) {
        try {
            logStatement.run();
        } catch (Exception e) {
            log.warn("请求摘要日志记录失败（不影响调用）: {}", e.getMessage());
        }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "" : message;
    }
}
