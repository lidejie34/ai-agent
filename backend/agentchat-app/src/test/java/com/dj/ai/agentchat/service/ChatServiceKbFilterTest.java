package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.InvalidKbFilterException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10/11：ChatService 知识库检索过滤单测——kbProjects/kbTags 经 validate 校验
 * （非法 400 InvalidKbFilterException），合法时随 applyRagAdvisor 注入 advisor param；
 * 无过滤不注入（请求形态与迭代9 逐字节一致）；RAG 缺席时过滤 warn 忽略不报错。
 * 迭代11：项目多选——kbProjects 列表注入、去重规整、空集合缺省注入。
 */
class ChatServiceKbFilterTest {

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
    void sync_withFilter_injectsAdvisorParams() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("售后政策", null, null, null,
                List.of("订单域", "物流域"), List.of("售后", "退货")));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_PROJECTS, List.of("订单域", "物流域"));
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_TAGS, List.of("售后", "退货"));
    }

    @Test
    void sync_multiProjects_normalizedDedupBeforeInject() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("售后政策", null, null, null,
                List.of("订单域", " 订单域 ", "物流域"), null));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_PROJECTS, List.of("订单域", "物流域"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_withFilter_injectsAdvisorParamsInsideDefer() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        StepVerifier.create(service.chatStream(
                        new ChatRequest("售后政策", null, null, null, null, List.of("售后"))).chunks())
                .expectNext("a").verifyComplete();

        verify(spec).advisors(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noFilter_neverInjectsParams_byteEquivalentToIteration9() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("你好", null));

        verify(spec).advisors(advisor); // advisor 照常挂载
        verify(spec, never()).advisors(any(Consumer.class)); // 但无 param 注入
    }

    @Test
    void tagsOnlyFilter_skipsEmptyProjectsParam() {
        // projects 空集合缺省注入（与迭代10 null project 缺省同纪律）
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        service.chat(new ChatRequest("承运规则", null, null, null, null, List.of("承运")));

        ChatClient.AdvisorSpec advisorSpec = captureAdvisorSpecConsumer();
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_PROJECTS), any());
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_TAGS, List.of("承运"));
    }

    @Test
    void invalidFilter_rejectedBeforeModelCall() {
        RagAdvisor advisor = mock(RagAdvisor.class);
        ChatService service = serviceWithRag(advisor);

        assertThatThrownBy(() -> service.chat(
                new ChatRequest("问题", null, null, null, List.of("含,逗号"), null)))
                .isInstanceOf(InvalidKbFilterException.class)
                .hasMessageContaining("知识库过滤参数非法");
        assertThatThrownBy(() -> service.chat(
                new ChatRequest("问题", null, null, null, List.of("合法项目", "含,逗号"), null)))
                .isInstanceOf(InvalidKbFilterException.class)
                .hasMessageContaining("知识库过滤参数非法");
        assertThatThrownBy(() -> service.chat(
                new ChatRequest("问题", null, null, null, null,
                        List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"))))
                .isInstanceOf(InvalidKbFilterException.class)
                .hasMessageContaining("8");
        verify(chatClient, never()).prompt();
    }

    @Test
    @SuppressWarnings("unchecked")
    void filterWithRagAbsent_warnAndIgnore_noParamsInjected() {
        // RAG 关闭语义：advisor provider 为 null——过滤参数 warn 忽略，不报错、不挂载
        ChatService service = new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, null, null, null);

        service.chat(new ChatRequest("问题", null, null, null, List.of("订单域"), null));

        verify(spec, never()).advisors(any(RagAdvisor.class));
        verify(spec, never()).advisors(any(Consumer.class));
    }
}
