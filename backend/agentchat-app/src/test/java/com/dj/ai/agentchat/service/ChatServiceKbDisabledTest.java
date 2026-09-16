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
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三下拉「都不加载」：ChatService 显式 kbProjects:[] 语义单测——
 * 显式空数组 = 本轮完全不加载知识库：挂载 {@link RagAdvisor#PARAM_KB_DISABLED}=true
 * advisor param（RagAdvisor 据此直接放行），projects/tags param 均不注入；
 * kbTags 宽松忽略（含非法 tags 也不 400，NFR-3）；RAG 缺席时静默忽略不报错。
 * 缺键=null=默认全部（既有 ChatServiceKbFilterTest 钉死，不在此重复）。
 */
class ChatServiceKbDisabledTest {

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
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));
        when(streamSpec.content()).thenReturn(Flux.just("a"));
    }

    private ChatService serviceWithRag(RagAdvisor advisor) {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, null, null,
                new ChatService.FixedObjectProvider<>(advisor));
    }

    @SuppressWarnings("unchecked")
    private ChatClient.AdvisorSpec captureAdvisorSpecConsumer() {
        org.mockito.ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(captor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(any(String.class), any())).thenReturn(advisorSpec);
        captor.getValue().accept(advisorSpec);
        return advisorSpec;
    }

    @Test
    void sync_explicitEmptyProjects_injectsDisabledParamOnly() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("售后政策", null, null, null, List.of(), null));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE);
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_PROJECTS), any());
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_TAGS), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_explicitEmptyProjects_injectsDisabledParamInsideDefer() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        StepVerifier.create(service.chatStream(
                        new ChatRequest("售后政策", null, null, null, List.of(), null)).chunks())
                .expectNext("a").verifyComplete();

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE);
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_PROJECTS), any());
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_TAGS), any());
    }

    @Test
    void emptyProjectsWithTags_tagsLenientlyIgnored_disabledOnly() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("售后政策", null, null, null, List.of(), List.of("售后")));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE);
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_TAGS), any());
    }

    @Test
    void emptyProjectsWithInvalidTags_no400_disabledOnly() {
        // NFR-3：显式 [] 时 tags 宽松忽略——即使 tags 本身非法（带逗号）也不 400
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("售后政策", null, null, null, List.of(), List.of("含,逗号")));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyProjects_ragAbsent_silentlyIgnored_noError() {
        // RAG 关闭（advisor 缺席）：显式 [] 静默忽略——反正不会检索，不报错不挂载
        ChatService service = new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, null, null, null);

        service.chat(new ChatRequest("问题", null, null, null, List.of(), null));

        verify(spec, never()).advisors(any(RagAdvisor.class));
        verify(spec, never()).advisors(any(Consumer.class));
    }
}
