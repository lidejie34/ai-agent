package com.dj.ai.agentchat.advisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3/T4：{@link RequestLoggingAdvisor} 单测——同步/流式双路径摘要日志（消息数/模型/耗时/成败/片段数）、
 * 异常摘要、信号不被改变；安全红线（AC-5）：伪造 Key 串与 Authorization/Bearer 绝不出现在任何日志。
 */
class RequestLoggingAdvisorTest {

    /** 伪造凭证：若日志泄漏请求正文/Header，该串会被断言命中。 */
    private static final String FAKE_SECRET = "sk-fake-secret-xyz-1234567890";

    private ListAppender<ILoggingEvent> appender;
    private Logger advisorLogger;

    @BeforeEach
    void setUp() {
        advisorLogger = (Logger) LoggerFactory.getLogger(RequestLoggingAdvisor.class);
        appender = new ListAppender<>();
        appender.start();
        advisorLogger.addAppender(appender);
        advisorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        advisorLogger.detachAppender(appender);
    }

    /**
     * 构造 M7 真实形态的 AdvisedRequest：{@code .messages(...)} 的最后一条 UserMessage
     * 已被 DefaultChatClient.toAdvisedRequest 提升为 userText 并从 messages 移除，
     * 故 messages 只放历史消息；history 为 null 表示单轮（messages 为空）。
     */
    private AdvisedRequest request(String userText, String optionsModel) {
        return request(userText, optionsModel, null, null);
    }

    private AdvisedRequest request(String userText, String optionsModel,
                                   List<org.springframework.ai.chat.messages.Message> history,
                                   String systemText) {
        AdvisedRequest.Builder builder = AdvisedRequest.builder()
                .chatModel(mock(org.springframework.ai.chat.model.ChatModel.class))
                .userText(userText)
                .messages(history == null ? List.of() : history);
        if (systemText != null) {
            builder.systemText(systemText);
        }
        if (optionsModel != null) {
            builder.chatOptions(OpenAiChatOptions.builder().model(optionsModel).build());
        }
        return builder.build();
    }

    private AdvisedResponse advisedResponse(String text) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new AdvisedResponse(chatResponse, new HashMap<>());
    }

    private List<String> allLogs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNoSecretLeak() {
        assertThat(allLogs())
                .noneMatch(msg -> msg.contains(FAKE_SECRET))
                .noneMatch(msg -> msg.contains("Authorization"))
                .noneMatch(msg -> msg.contains("Bearer"));
    }

    // ---------- T3：同步路径 ----------

    @Test
    void call_success_logsSummaryWithMessageCountModelAndElapsed() {
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("fallback-model");
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        AdvisedResponse response = advisedResponse("回答");
        when(chain.nextAroundCall(any())).thenReturn(response);

        AdvisedRequest req = request("你好，我的密钥是 " + FAKE_SECRET, "options-model");
        AdvisedResponse result = advisor.aroundCall(req, chain);

        assertThat(result).isSameAs(response);
        assertThat(allLogs())
                .anyMatch(msg -> msg.contains("模型调用开始[call]") && msg.contains("消息数=1")
                        && msg.contains("model=options-model"))
                .anyMatch(msg -> msg.contains("模型调用成功[call]") && msg.contains("耗时="))
                // debug 级只打长度，不打正文（密钥随正文一起被挡住）
                .anyMatch(msg -> msg.contains("userText长度=") && !msg.contains(FAKE_SECRET));
        assertNoSecretLeak();
    }

    @Test
    void call_multiTurnWithSystemPrompt_countsHistoryPlusUserPlusSystem() {
        // M7 形态：messages=历史（本轮 user 已被提升为 userText），systemText 非空
        List<org.springframework.ai.chat.messages.Message> history = List.of(
                new UserMessage("我叫小明"),
                new AssistantMessage("你好，小明！"));
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("fallback-model");
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any())).thenReturn(advisedResponse("小明你好"));

        advisor.aroundCall(
                request("我叫什么？", "options-model", history, "你是测试助手"), chain);

        // 2 历史 + 1 本轮 user + 1 system = 4；单轮无历史场景（其余用例）userText=1
        assertThat(allLogs())
                .anyMatch(msg -> msg.contains("模型调用开始[call]") && msg.contains("消息数=4"));
    }

    @Test
    void call_failure_logsReasonSummaryAndRethrows() {
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("fallback-model");
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any()))
                .thenThrow(new TransientAiException("429 Too Many Requests"));

        AdvisedRequest req = request("hi " + FAKE_SECRET, null);
        assertThatThrownBy(() -> advisor.aroundCall(req, chain))
                .isInstanceOf(TransientAiException.class);

        assertThat(allLogs())
                .anyMatch(msg -> msg.contains("模型调用失败[call]")
                        && msg.contains("TransientAiException")
                        && msg.contains("429 Too Many Requests"))
                .anyMatch(msg -> msg.contains("model=fallback-model"));
        assertNoSecretLeak();
    }

    // ---------- T4：流式路径 ----------

    @Test
    void stream_success_logsChunkCountAndPassesSignalsThrough() {
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("fallback-model");
        StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
        AdvisedResponse r1 = advisedResponse("你");
        AdvisedResponse r2 = advisedResponse("好");
        when(chain.nextAroundStream(any())).thenReturn(Flux.just(r1, r2));

        Flux<AdvisedResponse> flux = advisor.aroundStream(
                request("说你好 " + FAKE_SECRET, "options-model"), chain);

        StepVerifier.create(flux)
                .expectNext(r1)
                .expectNext(r2)
                .verifyComplete();

        assertThat(allLogs())
                .anyMatch(msg -> msg.contains("模型调用开始[stream]") && msg.contains("消息数=1"))
                .anyMatch(msg -> msg.contains("模型流式调用成功[stream]")
                        && msg.contains("片段数=2") && msg.contains("耗时="));
        assertNoSecretLeak();
    }

    @Test
    void stream_failure_logsReasonAndErrorSignalPassesThrough() {
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("fallback-model");
        StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
        when(chain.nextAroundStream(any()))
                .thenReturn(Flux.error(new TransientAiException("503 upstream")));

        Flux<AdvisedResponse> flux = advisor.aroundStream(request("hi", null), chain);

        StepVerifier.create(flux)
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(TransientAiException.class))
                .verify();

        assertThat(allLogs())
                .anyMatch(msg -> msg.contains("模型流式调用失败[stream]")
                        && msg.contains("TransientAiException")
                        && msg.contains("503 upstream"));
        assertNoSecretLeak();
    }

    @Test
    void getNameAndOrder_followM7AdvisorContract() {
        RequestLoggingAdvisor advisor = new RequestLoggingAdvisor("m");
        assertThat(advisor.getName()).isEqualTo("request-logging-advisor");
        assertThat(advisor.getOrder()).isInstanceOf(Integer.class);
    }
}
