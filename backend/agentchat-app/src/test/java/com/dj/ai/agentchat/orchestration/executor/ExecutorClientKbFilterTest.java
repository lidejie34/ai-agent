package com.dj.ai.agentchat.orchestration.executor;

import com.dj.ai.agentchat.dto.KbFilter;
import com.dj.ai.agentchat.orchestration.OrchInput;
import com.dj.ai.agentchat.orchestration.SddProperties;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.planner.PlannerContext;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10：ExecutorClient 知识库过滤单测——OrchInput 透传的 KbFilter 非空且 advisor
 * 在场时注入 RagAdvisor advisor param；null/空过滤不注入（请求形态与迭代9 逐字节一致）；
 * advisor 缺席时过滤静默忽略。附 OrchInput 兼容构造回归。
 * 迭代11：项目多选——projects 列表注入、空集合缺省注入。
 */
class ExecutorClientKbFilterTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private OrchestrationAuditService audit;
    private RagAdvisor advisor;
    private ObjectProvider<RagAdvisor> advisorProvider;
    private ExecutorClient client;

    private final TaskSpec task = new TaskSpec("t1", "查售后政策", "整理退货规则");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.advisors(any(RagAdvisor.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("{\"ok\":true,\"result\":\"r\"}")))));
        audit = mock(OrchestrationAuditService.class);
        advisor = mock(RagAdvisor.class);
        advisorProvider = mock(ObjectProvider.class);
        when(advisorProvider.getIfAvailable()).thenReturn(advisor);
        // 6 参兼容构造：observability 默认关闭 → 无其他 Consumer advisors 干扰
        client = new ExecutorClient(chatClient, new SddProperties(),
                new ModelInvoker(Executors.newFixedThreadPool(2)), audit, null, advisorProvider);
    }

    private static PlannerContext ctx() {
        return PlannerContext.route("run-1", "sid-1", "售后政策", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void kbFilterPresent_injectsAdvisorParams() {
        TaskOutcome o = client.execute(task, null, ctx(), 0,
                new KbFilter(List.of("订单域", "物流域"), List.of("售后", "退货")));

        assertThat(o.ok()).isTrue();
        verify(spec).advisors(advisor);
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(captor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), any())).thenReturn(advisorSpec);
        captor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_PROJECTS, List.of("订单域", "物流域"));
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_TAGS, List.of("售后", "退货"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kbFilterNull_noParamInjection_byteEquivalentToIteration9() {
        TaskOutcome o = client.execute(task, null, ctx(), 0);

        assertThat(o.ok()).isTrue();
        verify(spec).advisors(advisor);
        verify(spec, never()).advisors(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kbFilterEmpty_noParamInjection() {
        TaskOutcome o = client.execute(task, null, ctx(), 0,
                new KbFilter(List.of(), List.of()));

        assertThat(o.ok()).isTrue();
        verify(spec).advisors(advisor);
        verify(spec, never()).advisors(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagsOnlyFilter_skipsEmptyProjectsParam() {
        TaskOutcome o = client.execute(task, null, ctx(), 0,
                new KbFilter(List.of(), List.of("承运")));

        assertThat(o.ok()).isTrue();
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(captor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), any())).thenReturn(advisorSpec);
        captor.getValue().accept(advisorSpec);
        verify(advisorSpec, never()).param(eq(RagAdvisor.PARAM_KB_PROJECTS), any());
        verify(advisorSpec).param(RagAdvisor.PARAM_KB_TAGS, List.of("承运"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kbFilterPresentButAdvisorAbsent_silentlyIgnored() {
        when(advisorProvider.getIfAvailable()).thenReturn(null);

        TaskOutcome o = client.execute(task, null, ctx(), 0,
                new KbFilter(List.of("订单域"), List.of("售后")));

        assertThat(o.ok()).isTrue();
        verify(spec, never()).advisors(any(RagAdvisor.class));
        verify(spec, never()).advisors(any(Consumer.class));
    }

    @Test
    void orchInput_legacyCtor_delegatesNullKbFilter() {
        OrchInput in = new OrchInput("run-1", "你好", List.of(), "sid-1", null,
                Duration.ofSeconds(10), false, () -> null, () -> "降级");

        assertThat(in.kbFilter()).isNull();
        assertThat(in.runId()).isEqualTo("run-1");
    }
}
