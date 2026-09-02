package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.ChatNotConfiguredException;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.ModelCallException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话服务（厚层）：请求校验、多轮消息组装、ChatClient 同步/流式调用、异常归一。
 *
 * <p>服务端无会话状态：每次请求现场组装 Prompt（history 正序 + 本轮用户消息）。
 * 不直接面向 OpenAiChatModel 写业务代码，模型客户端细节被 Spring AI 与 config 层隔离。
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

    private final ChatClient chatClient;
    private final String apiKey;
    private final String configuredModel;

    public ChatService(ChatClient chatClient,
                       @Value("${spring.ai.openai.api-key:}") String apiKey,
                       @Value("${spring.ai.openai.chat.options.model:}") String configuredModel) {
        this.chatClient = chatClient;
        this.apiKey = apiKey;
        this.configuredModel = configuredModel;
    }

    /**
     * 同步问答：等待完整回复后一次性返回。
     */
    public ChatResponse chat(ChatRequest request) {
        validate(request);
        ensureConfigured();
        List<Message> messages = buildMessages(request);
        log.debug("同步调用模型: 消息总数={}, 配置model={}", messages.size(), configuredModel);
        try {
            org.springframework.ai.chat.model.ChatResponse aiResponse =
                    chatClient.prompt().messages(messages).call().chatResponse();
            String reply = extractReply(aiResponse);
            String model = resolveModel(aiResponse);
            return new ChatResponse(reply, model);
        } catch (InvalidChatRequestException | ChatNotConfiguredException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("同步模型调用异常: {}", e.getMessage());
            throw new ModelCallException(MODEL_FAILED_MESSAGE, e);
        }
    }

    /**
     * 流式问答：返回逐段文本 Flux（由控制器订阅推送 SSE）。
     * 校验/缺 Key 在装配 Flux 前同步抛出；模型侧异常经 onErrorMap 归一为 {@link ModelCallException}。
     */
    public Flux<String> chatStream(ChatRequest request) {
        validate(request);
        ensureConfigured();
        List<Message> messages = buildMessages(request);
        log.debug("流式调用模型: 消息总数={}, 配置model={}", messages.size(), configuredModel);
        try {
            return chatClient.prompt().messages(messages).stream().content()
                    .onErrorMap(RuntimeException.class,
                            e -> (e instanceof ModelCallException) ? e : new ModelCallException(STREAM_FAILED_MESSAGE, e));
        } catch (RuntimeException e) {
            log.warn("流式装配异常: {}", e.getMessage());
            throw new ModelCallException(STREAM_FAILED_MESSAGE, e);
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

    /**
     * 多轮组装：history 正序（user→UserMessage，assistant→AssistantMessage），末尾追加本轮用户消息。
     */
    private List<Message> buildMessages(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.history() != null) {
            for (ChatMessage h : request.history()) {
                if ("assistant".equals(h.role())) {
                    messages.add(new AssistantMessage(h.content()));
                } else {
                    messages.add(new UserMessage(h.content()));
                }
            }
        }
        messages.add(new UserMessage(request.message()));
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
}
