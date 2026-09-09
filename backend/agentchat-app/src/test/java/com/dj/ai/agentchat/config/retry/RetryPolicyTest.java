package com.dj.ai.agentchat.config.retry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T8/T9/T10：同步重试策略断言（AC-8/AC-9/AC-10）。
 *
 * <p>手法：{@link ChatRetryConfig} 产出的 {@link RetryTemplate} 经一个「与 OpenAiChatModel 同款」的
 * 委托模型（call() 内 retryTemplate.execute 包裹模型终调）接入<b>真实 ChatClient</b>；
 * 模型终调 mock，离线无外网。退避用毫秒级配置值，断言只看调用次数/最终结果/重试日志，不等待真实退避。
 */
class RetryPolicyTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger retryLogger;

    @BeforeEach
    void setUp() {
        retryLogger = (Logger) LoggerFactory.getLogger(ChatRetryConfig.class);
        appender = new ListAppender<>();
        appender.start();
        retryLogger.addAppender(appender);
        retryLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        retryLogger.detachAppender(appender);
    }

    private RetryTemplate retryTemplate(int maxAttempts) {
        SpringAiRetryProperties properties = new SpringAiRetryProperties();
        properties.setMaxAttempts(maxAttempts);
        // M7 实证：backoff 字段由构造器初始化、无 setter，经 getter 取得后改写
        SpringAiRetryProperties.Backoff backoff = properties.getBackoff();
        backoff.setInitialInterval(Duration.ofMillis(10));
        backoff.setMultiplier(2);
        backoff.setMaxInterval(Duration.ofMillis(50));
        return new ChatRetryConfig().retryTemplate(properties);
    }

    /** 与 OpenAiChatModel 同构：同步终调包在 RetryTemplate.execute 中；流式不走 RetryTemplate（M7 实证）。 */
    private ChatModel retryingModel(ChatModel delegate, RetryTemplate retryTemplate) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return retryTemplate.execute(ctx -> delegate.call(prompt));
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return delegate.stream(prompt);
            }
        };
    }

    private ChatResponse aiResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ---------- T8：可重试故障重试后成功 ----------

    @Test
    void transientFailure_thenSuccess_retriesThreeTimes_andLogsRetries() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("429 Too Many Requests"))
                .thenThrow(new TransientAiException("503 Service Unavailable"))
                .thenReturn(aiResponse("最终成功"));
        ChatClient client = ChatClient.create(retryingModel(delegate, retryTemplate(3)));

        String reply = client.prompt().user("你好").call().content();

        assertThat(reply).isEqualTo("最终成功");
        verify(delegate, times(3)).call(any(Prompt.class));
        List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs).anyMatch(m -> m.contains("第 1 次尝试失败") && m.contains("TransientAiException"));
        assertThat(logs).anyMatch(m -> m.contains("第 2 次尝试失败") && m.contains("TransientAiException"));
    }

    @Test
    void resourceAccessWrappedInCause_retriedViaTraversingCauses() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("调用失败", new ResourceAccessException("Connection reset")))
                .thenThrow(new ResourceAccessException("connect timed out"))
                .thenReturn(aiResponse("恢复"));
        ChatClient client = ChatClient.create(retryingModel(delegate, retryTemplate(3)));

        String reply = client.prompt().user("你好").call().content();

        assertThat(reply).isEqualTo("恢复");
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    // ---------- T9：重试耗尽明确失败 ----------

    @Test
    void alwaysTransient_retriesThreeTimesThenThrows_andLogsExhausted() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenThrow(new TransientAiException("500 Internal Server Error"));
        ChatClient client = ChatClient.create(retryingModel(delegate, retryTemplate(3)));

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .isInstanceOf(TransientAiException.class);
        verify(delegate, times(3)).call(any(Prompt.class));
        List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs).anyMatch(m -> m.contains("重试已耗尽") && m.contains("共 3 次尝试"));
    }

    // ---------- T10：不可重试故障立即失败 ----------

    @Test
    void nonTransientFailure_notRetried_calledOnce() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("400 Bad Request"));
        ChatClient client = ChatClient.create(retryingModel(delegate, retryTemplate(3)));

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .isInstanceOf(NonTransientAiException.class);
        verify(delegate, times(1)).call(any(Prompt.class));
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(m -> m.contains("重试已耗尽"));
    }

    @Test
    void resourceAccess_notRetriedWhenTraversingCausesDisabled() {
        // 反向验证：未开 traversingCauses 的默认策略下，被包装的网络异常不重试（M7 自动配置默认行为）
        RetryTemplate defaultStyle = RetryTemplate.builder()
                .maxAttempts(3)
                .retryOn(TransientAiException.class)
                .noBackoff()
                .build();
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException(new ResourceAccessException("read timeout")));
        ChatClient client = ChatClient.create(retryingModel(delegate, defaultStyle));

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .hasCauseInstanceOf(ResourceAccessException.class);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    void stream_path_doesNotUseRetryTemplate_delegateCalledDirectly() {
        // M7 实证边界：流式 internalStream 不引用 RetryTemplate，框架层不重试（应用层重试在 ChatService）
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.just(aiResponse("a"), aiResponse("b")));
        ChatClient client = ChatClient.create(retryingModel(delegate, retryTemplate(3)));

        List<String> chunks = client.prompt().user("你好").stream().content().collectList().block();

        assertThat(chunks).containsExactly("a", "b");
        verify(delegate, never()).call(any(Prompt.class));
    }
}
