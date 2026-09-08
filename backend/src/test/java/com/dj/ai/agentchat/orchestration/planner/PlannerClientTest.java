package com.dj.ai.agentchat.orchestration.planner;

import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.orchestration.SddProperties;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.support.CapReasons;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.orchestration.support.ObservationText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerClient 单元测试（迭代5，T3）：mock ChatClient 规格链，验证路由/再规划/汇总的
 * 请求级角色差异（无工具、系统提示词回退/配置覆盖、options 仅在配置时设置）、
 * 每次模型调用新建 spec、解析失败纠正重试 1 次、异常不重试、审计落库与 Synth 流式审计。
 */
class PlannerClientTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private SddProperties props;
    private OrchestrationAuditService audit;
    private PlannerClient client;

    private static final String PLAN_JSON = """
            {"mode":"plan","tasks":[
              {"taskId":"t1","title":"查询错误日志","goal":"统计 ERROR 数量"},
              {"taskId":"t2","title":"给出结论","goal":"基于 t1 结果回答"}
            ]}""";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.tools(anyList())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("片段1", "片段2"));
        props = new SddProperties();
        audit = mock(OrchestrationAuditService.class);
        client = new PlannerClient(chatClient, props,
                new ModelInvoker(Executors.newFixedThreadPool(2)), audit);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse chatResponse(String text, String model) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).build());
    }

    private static PlannerContext ctx() {
        return PlannerContext.route("run-1", "sid-1", "帮我分析日志", List.of());
    }

    @SuppressWarnings("unchecked")
    private static String firstUserText(ArgumentCaptor<List<Message>> captor) {
        List<Message> messages = captor.getValue();
        return ((UserMessage) messages.get(messages.size() - 1)).getText();
    }

    @Test
    void route_plan_parsed_audited_noTools_defaultSystem_noOptions() {
        when(callSpec.chatResponse()).thenReturn(chatResponse(PLAN_JSON));

        RouteDecision d = client.route(ctx(), 0);

        assertThat(d).isInstanceOf(RouteDecision.Plan.class);
        RouteDecision.Plan plan = (RouteDecision.Plan) d;
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks().get(0).taskId()).isEqualTo("t1");
        // 每次调用新建 spec；Planner 永不挂工具；未配置角色 options 时不调 .options()
        verify(chatClient, times(1)).prompt();
        verify(spec, never()).tools(anyList());
        verify(spec, never()).options(any());
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(spec).system(sys.capture());
        assertThat(sys.getValue()).startsWith("你是任务规划者");
        verify(audit).record(eq("run-1"), eq("sid-1"), eq(1),
                eq(OrchestrationAuditService.ROLE_PLANNER), nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), nullable(String.class), nullable(String.class));
    }

    @Test
    void route_direct_returnsDirect() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"mode\":\"direct\",\"answer\":\"你好呀\"}"));

        RouteDecision d = client.route(ctx(), 0);

        assertThat(d).isInstanceOf(RouteDecision.Direct.class);
        assertThat(((RouteDecision.Direct) d).answer()).isEqualTo("你好呀");
    }

    @Test
    void route_unparseable_retriesOnceWithCorrection_thenSucceeds() {
        when(callSpec.chatResponse())
                .thenReturn(chatResponse("我觉得这个问题嘛……不好说"))
                .thenReturn(chatResponse(PLAN_JSON));

        RouteDecision d = client.route(ctx(), 0);

        assertThat(d).isInstanceOf(RouteDecision.Plan.class);
        verify(chatClient, times(2)).prompt();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(spec, times(2)).messages(msgs.capture());
        List<List<Message>> all = msgs.getAllValues();
        String secondUser = ((UserMessage) all.get(1).get(0)).getText();
        assertThat(secondUser).contains(SddPrompts.ROUTE_RETRY_INSTRUCTION);
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_PLANNER),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), nullable(String.class), nullable(String.class));
    }

    @Test
    void route_unparseableTwice_returnsUnparseable_andAuditsFailed() {
        when(callSpec.chatResponse())
                .thenReturn(chatResponse("乱码输出一"))
                .thenReturn(chatResponse("乱码输出二"));

        RouteDecision d = client.route(ctx(), 0);

        assertThat(d).isInstanceOf(RouteDecision.Unparseable.class);
        verify(chatClient, times(2)).prompt();
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_PLANNER),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                contains("无法解析"));
    }

    @Test
    void route_modelException_auditsFailed_rethrows_noRetry() {
        when(callSpec.chatResponse()).thenThrow(new ModelCallException("boom-502", new RuntimeException("upstream")));

        assertThatThrownBy(() -> client.route(ctx(), 0))
                .isInstanceOf(ModelCallException.class)
                .hasMessageContaining("boom-502");

        // 模型异常不做协议重试（区别于 Unparseable）
        verify(chatClient, times(1)).prompt();
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_PLANNER),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                contains("boom-502"));
    }

    @Test
    void replan_next_parsesTaskAndSkipped_userMessageCarriesObservations() {
        PlannerContext c = ctx().forReplan(2, "1. t1 查询错误日志\n2. t2 给出结论",
                List.of(new ObservationText("t1", "查询错误日志", "succeeded", "ERROR 共 12 条")));
        when(callSpec.chatResponse()).thenReturn(chatResponse(
                "{\"action\":\"next\",\"task\":{\"taskId\":\"t3\",\"title\":\"核对发布时间\",\"goal\":\"对照发布记录\"},\"skipped\":[\"t2\"]}"));

        ReplanDecision d = client.replan(c, 0);

        assertThat(d).isInstanceOf(ReplanDecision.Next.class);
        ReplanDecision.Next next = (ReplanDecision.Next) d;
        assertThat(next.task().title()).isEqualTo("核对发布时间");
        assertThat(next.skippedTaskIds()).containsExactly("t2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(spec).messages(msgs.capture());
        String user = firstUserText(msgs);
        assertThat(user).contains("ERROR 共 12 条").contains("原始计划");
        verify(spec, never()).tools(anyList());
        verify(audit).record(eq("run-1"), eq("sid-1"), eq(2),
                eq(OrchestrationAuditService.ROLE_PLANNER), nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), nullable(String.class), nullable(String.class));
    }

    @Test
    void replan_final_returnsFinal() {
        PlannerContext c = ctx().forReplan(2, "计划",
                List.of(new ObservationText("t1", "查询", "succeeded", "结果")));
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"action\":\"final\"}"));

        assertThat(client.replan(c, 0)).isInstanceOf(ReplanDecision.Final.class);
    }

    @Test
    void replan_unparseableTwice_returnsUnparseable_andAuditsFailed() {
        PlannerContext c = ctx().forReplan(2, "计划", List.of());
        when(callSpec.chatResponse())
                .thenReturn(chatResponse("不是 JSON 一"))
                .thenReturn(chatResponse("不是 JSON 二"));

        ReplanDecision d = client.replan(c, 0);

        assertThat(d).isInstanceOf(ReplanDecision.Unparseable.class);
        verify(chatClient, times(2)).prompt();
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_PLANNER),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                contains("无法解析"));
    }

    @Test
    void synthStream_emitsContent_auditsSuccess_systemHasSuffix_userHasForceFinish() {
        PlannerContext c = ctx().forSynth(3, "计划",
                List.of(new ObservationText("t1", "查询错误日志", "succeeded", "ERROR 共 12 条")),
                CapReasons.ROUNDS);
        when(streamSpec.content()).thenReturn(Flux.just("综合", "答案"));

        StepVerifier.create(client.synthStream(c))
                .expectNext("综合")
                .expectNext("答案")
                .verifyComplete();

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(spec).system(sys.capture());
        assertThat(sys.getValue()).contains("现在进入汇总阶段");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(spec).messages(msgs.capture());
        String user = firstUserText(msgs);
        assertThat(user).contains("ERROR 共 12 条").contains(CapReasons.ROUNDS);
        verify(spec, never()).tools(anyList());
        verify(audit).record(eq("run-1"), eq("sid-1"), eq(3),
                eq(OrchestrationAuditService.ROLE_SYNTH), nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), nullable(String.class), nullable(String.class));
    }

    @Test
    void synthStream_modelError_auditsFailed() {
        PlannerContext c = ctx().forSynth(2, "计划", List.of(), null);
        when(streamSpec.content()).thenReturn(
                Flux.error(new ModelCallException("stream-boom", new RuntimeException("upstream"))));

        StepVerifier.create(client.synthStream(c))
                .expectError(ModelCallException.class)
                .verify();

        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_SYNTH),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                contains("stream-boom"));
    }

    @Test
    void roleConfigOverrides_appliedToSpec_andModelFromMetadataAudited() {
        props.getPlanner().setModel("planner-model-x");
        props.getPlanner().setTemperature(0.3d);
        props.getPlanner().setSystemPrompt("自定义规划者提示词XYZ");
        when(callSpec.chatResponse()).thenReturn(chatResponse(PLAN_JSON, "planner-model-x"));

        client.route(ctx(), 0);

        verify(spec).system("自定义规划者提示词XYZ");
        ArgumentCaptor<ChatOptions> opt = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(opt.capture());
        assertThat(opt.getValue()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions oo = (OpenAiChatOptions) opt.getValue();
        assertThat(oo.getModel()).isEqualTo("planner-model-x");
        assertThat(oo.getTemperature()).isEqualTo(0.3d);
        // 响应元数据中的 model 透传审计
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_PLANNER),
                nullable(String.class), nullable(String.class),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), eq("planner-model-x"),
                nullable(String.class));
    }
}
