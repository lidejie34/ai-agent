package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T8（迭代6）：ChatService 仅在 RAG 开关开启时请求级挂载 RagAdvisor——
 * 同步/流式（Flux.defer 内每次订阅）两个位点；缺席时不调用 .advisors()，
 * 请求形态与迭代5 逐字节一致。Planner/Synth 不经本类，天然零挂载。
 */
class ChatServiceRagAdvisorMountTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.messages(any(List.class))).thenReturn(spec);
        when(spec.advisors(any(RagAdvisor.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));
        when(streamSpec.content()).thenReturn(Flux.just("a", "b"));
    }

    private ChatService serviceWithRag(RagAdvisor advisor) {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, null, null,
                new ChatService.FixedObjectProvider<>(advisor));
    }

    private ChatService serviceWithoutRag() {
        // app.rag.enabled=false 语义：RagAdvisor ObjectProvider 传 null
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, null, null, null);
    }

    @Test
    void sync_ragEnabled_mountsAdvisorAtRequestLevel() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("差旅报销标准", null));

        verify(spec).advisors(advisor);
    }

    @Test
    void stream_ragEnabled_mountsAdvisorInsideFluxDefer() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        StepVerifier.create(service.chatStream(new ChatRequest("差旅报销标准", null)).chunks())
                .expectNext("a").expectNext("b").verifyComplete();

        verify(spec).advisors(advisor);
    }

    @Test
    void sync_ragAbsent_neverCallsAdvisors_byteEquivalentToIteration5() {
        serviceWithoutRag().chat(new ChatRequest("你好", null));

        verify(spec, never()).advisors(any(RagAdvisor.class));
    }

    @Test
    void stream_ragAbsent_neverCallsAdvisors() {
        StepVerifier.create(serviceWithoutRag()
                        .chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("a").expectNext("b").verifyComplete();

        verify(spec, never()).advisors(any(RagAdvisor.class));
    }

    @Test
    void stream_resubscription_remountsAdvisor_eachDeferBuildsChain() {
        ChatClient.StreamResponseSpec second = mock(ChatClient.StreamResponseSpec.class);
        when(second.content()).thenReturn(Flux.just("ok"));
        when(spec.stream())
                .thenThrow(new RuntimeException(new java.io.IOException("connection reset")))
                .thenReturn(second);

        RagAdvisor advisor = mock(RagAdvisor.class);
        StepVerifier.create(serviceWithRag(advisor)
                        .chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("ok").verifyComplete();

        // 重试重订阅 → defer 内重建 spec 与 Advisor 链（与工具挂载同为每订阅一次）
        verify(spec, times(2)).advisors(advisor);
    }
}
