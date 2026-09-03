package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.ModelCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T11：流式路径首片段前有限重试（FR-4.5 / AC-8、AC-9 流式语义）。
 *
 * <p>M7 实证：OpenAiChatModel.stream/internalStream 不使用 RetryTemplate，框架层流式不重试；
 * 故 ChatService 在应用层用 Flux.retryWhen 仅对「尚未发出任何片段」的建连/订阅期可重试故障重试
 * （总尝试 3 次）；首片段后任何错误直接下传（error 事件），不重放、不重复内容。
 * 测试退避为毫秒级（构造默认值），无真实等待。
 */
class ChatServiceStreamRetryTest {

    private ChatModel chatModel;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        // 3 参构造为测试便捷构造（退避 10ms 起，离线快速确定）
        service = new ChatService(ChatClient.create(chatModel), "ark-test-key", "configured-model");
    }

    private ChatResponse aiResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatRequest request() {
        return new ChatRequest("慢慢说", null);
    }

    private WebClientResponseException httpError(int status) {
        return WebClientResponseException.create(status, "HTTP " + status,
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void stream_transient5xxBeforeFirstChunk_retriedThenSucceeds_threeAttempts() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(httpError(503)))
                .thenReturn(Flux.error(httpError(429)))
                .thenReturn(Flux.just(aiResponse("你"), aiResponse("好")));

        StepVerifier.create(service.chatStream(request()).chunks())
                .expectNext("你")
                .expectNext("好")
                .verifyComplete();

        verify(chatModel, times(3)).stream(any(Prompt.class));
    }

    @Test
    void stream_ioFailureBeforeFirstChunk_retriedThenSucceeds() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException(new IOException("connection reset by peer"))))
                .thenReturn(Flux.just(aiResponse("恢复")));

        StepVerifier.create(service.chatStream(request()).chunks())
                .expectNext("恢复")
                .verifyComplete();

        verify(chatModel, times(2)).stream(any(Prompt.class));
    }

    @Test
    void stream_transientErrorAlwaysFails_retriesThreeTimesThenErrorEvent() {
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> Flux.error(httpError(500)));

        StepVerifier.create(service.chatStream(request()).chunks())
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ModelCallException.class))
                .verify();

        verify(chatModel, times(3)).stream(any(Prompt.class));
    }

    @Test
    void stream_4xxBeforeFirstChunk_notRetried() {
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> Flux.error(httpError(404)));

        StepVerifier.create(service.chatStream(request()).chunks())
                .expectError(ModelCallException.class)
                .verify();

        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void stream_errorAfterFirstChunk_notRetried_noReplay() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(aiResponse("片段一")), Flux.error(httpError(503))));

        StepVerifier.create(service.chatStream(request()).chunks())
                .expectNext("片段一")
                .expectError(ModelCallException.class)
                .verify();

        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void stream_missingKey_modelNeverCalled() {
        ChatService unconfigured = new ChatService(ChatClient.create(chatModel), "  ", "model");
        // 缺 Key 在装配 Flux 前同步抛出（迭代 1 行为保持）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unconfigured.chatStream(request()))
                .hasMessageContaining("ARK_API_KEY");
        verify(chatModel, never()).stream(any(Prompt.class));
    }
}
