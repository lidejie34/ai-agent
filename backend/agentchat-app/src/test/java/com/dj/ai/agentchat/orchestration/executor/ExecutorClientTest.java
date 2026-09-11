package com.dj.ai.agentchat.orchestration.executor;

import com.dj.ai.agentchat.orchestration.SddProperties;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.planner.PlannerContext;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolMount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
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
 * ExecutorClient 单元测试（迭代5，T3）：ok/error JSON 契约、非 JSON 整段当 result、
 * 围栏 JSON、结果脱敏与 2000 字截断、超时 TIMEOUT、模型异常转任务失败（不抛出）、
 * 共享工具挂载（mount null/空 → 不 .tools()）、角色 options/系统提示词覆盖、审计。
 */
class ExecutorClientTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private SddProperties props;
    private OrchestrationAuditService audit;
    private SecretRedactor redactor;
    private ToolMount mount;
    private final TaskSpec task = new TaskSpec("t1", "查询错误日志", "统计 ERROR 数量");

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.tools(anyList())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        props = new SddProperties();
        audit = mock(OrchestrationAuditService.class);
        redactor = new SecretRedactor(List.of());
        ToolCallback cb = mock(ToolCallback.class);
        mount = new ToolMount(List.of(cb),
                Map.of("sessionId", "sid-1", "requestId", "req-1"),
                mock(ToolCallBridge.class));
    }

    private ExecutorClient newClient() {
        return newClient(null);
    }

    private ExecutorClient newClient(ObjectProvider<RagAdvisor> ragAdvisorProvider) {
        return new ExecutorClient(chatClient, props,
                new ModelInvoker(Executors.newFixedThreadPool(2)), audit, redactor, ragAdvisorProvider);
    }

    private static PlannerContext ctx() {
        return PlannerContext.route("run-1", "sid-1", "帮我分析日志", List.of());
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse chatResponse(String text, String model) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).build());
    }

    @Test
    void execute_okJson_returnsSuccess_mountsSharedTools_userMessageCarriesTaskAndRequest() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":true,\"result\":\"ERROR 共 12 条\"}"));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isTrue();
        assertThat(o.text()).contains("ERROR 共 12 条");
        assertThat(o.timedOut()).isFalse();
        // 共享工具挂载：Executor 是唯一挂工具的角色
        verify(spec, times(1)).tools(anyList());
        verify(spec, times(1)).toolContext(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(spec).messages(msgs.capture());
        String user = ((UserMessage) msgs.getValue().get(0)).getText();
        assertThat(user).contains("查询错误日志").contains("统计 ERROR 数量").contains("帮我分析日志");
        // Executor 不带主会话历史（消息仅 1 条用户消息）
        assertThat(msgs.getValue()).hasSize(1);
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(spec).system(sys.capture());
        assertThat(sys.getValue()).startsWith("你是任务执行者");
        verify(audit).record(eq("run-1"), eq("sid-1"), eq(1),
                eq(OrchestrationAuditService.ROLE_EXECUTOR), eq("t1"), eq("查询错误日志"),
                eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(), nullable(String.class),
                nullable(String.class));
    }

    @Test
    void execute_errorJson_returnsFailure_andAuditsFailed() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":false,\"error\":\"数据库连接失败\"}"));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isFalse();
        assertThat(o.error()).contains("数据库连接失败");
        assertThat(o.timedOut()).isFalse();
        assertThat(o.text()).isNull();
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_EXECUTOR),
                eq("t1"), eq("查询错误日志"),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                contains("数据库连接失败"));
    }

    @Test
    void execute_nonJson_wholeTextTreatedAsResult() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("任务已完成，关键结论是 42。"));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isTrue();
        assertThat(o.text()).contains("42");
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_EXECUTOR),
                eq("t1"), any(), eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(),
                nullable(String.class), nullable(String.class));
    }

    @Test
    void execute_fencedJson_extractedAndParsed() {
        when(callSpec.chatResponse()).thenReturn(
                chatResponse("```json\n{\"ok\":true,\"result\":\"围栏内结果OK\"}\n```"));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isTrue();
        assertThat(o.text()).contains("围栏内结果OK");
    }

    @Test
    void execute_result_isRedacted_andTruncatedWithMarker() {
        props.getExecutor().setMaxResultChars(20);
        String secret = "ark-1234567890abcdef";
        when(callSpec.chatResponse()).thenReturn(
                chatResponse("{\"ok\":true,\"result\":\"密钥 " + secret + " " + "x".repeat(200) + "\"}"));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isTrue();
        assertThat(o.text()).doesNotContain(secret);
        assertThat(o.text()).contains("REDACTED");
        assertThat(o.text()).endsWith("…[观察结果已截断]");
        assertThat(o.text().length()).isLessThan(100);
    }

    @Test
    void execute_timeout_returnsTimedOutFailure_andAuditsTimeout() {
        when(callSpec.chatResponse()).thenAnswer(inv -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return chatResponse("{\"ok\":true,\"result\":\"迟到的结果\"}");
        });

        TaskOutcome o = newClient().execute(task, mount, ctx(), Duration.ofMillis(50).toNanos());

        assertThat(o.ok()).isFalse();
        assertThat(o.timedOut()).isTrue();
        assertThat(o.error()).contains("超时");
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_EXECUTOR),
                eq("t1"), eq("查询错误日志"),
                eq(OrchestrationAuditService.STATUS_TIMEOUT), anyLong(), nullable(String.class),
                contains("超时"));
    }

    @Test
    void execute_modelException_returnsFailureNotThrown_errorTruncatedTo500() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("500 " + "z".repeat(800)));

        TaskOutcome o = newClient().execute(task, mount, ctx(), 0);

        assertThat(o.ok()).isFalse();
        assertThat(o.timedOut()).isFalse();
        assertThat(o.error()).startsWith("模型调用失败");
        assertThat(o.error().length()).isLessThan(530);
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_EXECUTOR),
                eq("t1"), eq("查询错误日志"),
                eq(OrchestrationAuditService.STATUS_FAILED), anyLong(), nullable(String.class),
                anyString());
    }

    @Test
    void execute_mountNull_noToolsAttached() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":true,\"result\":\"无工具结果\"}"));

        TaskOutcome o = newClient().execute(task, null, ctx(), 0);

        assertThat(o.ok()).isTrue();
        verify(spec, never()).tools(anyList());
        verify(spec, never()).toolContext(any());
    }

    @Test
    void executorConfigOverrides_appliedToSpec_andModelFromMetadataAudited() {
        props.getExecutor().setModel("exec-model-y");
        props.getExecutor().setTemperature(0.1d);
        props.getExecutor().setSystemPrompt("自定义执行者提示词AAA");
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":true,\"result\":\"r\"}", "exec-model-y"));

        newClient().execute(task, mount, ctx(), 0);

        verify(spec).system("自定义执行者提示词AAA");
        ArgumentCaptor<ChatOptions> opt = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(opt.capture());
        assertThat(opt.getValue()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions oo = (OpenAiChatOptions) opt.getValue();
        assertThat(oo.getModel()).isEqualTo("exec-model-y");
        assertThat(oo.getTemperature()).isEqualTo(0.1d);
        verify(audit).record(anyString(), any(), anyInt(), eq(OrchestrationAuditService.ROLE_EXECUTOR),
                eq("t1"), any(), eq(OrchestrationAuditService.STATUS_SUCCESS), anyLong(),
                eq("exec-model-y"), nullable(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ragEnabled_executorRequestMountsAdvisor() {
        when(spec.advisors(any(RagAdvisor.class))).thenReturn(spec);
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":true,\"result\":\"r\"}"));
        RagAdvisor advisor = mock(RagAdvisor.class);
        ObjectProvider<RagAdvisor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(advisor);

        TaskOutcome o = newClient(provider).execute(task, null, ctx(), 0);

        assertThat(o.ok()).isTrue();
        verify(spec).advisors(advisor);
    }

    @Test
    void ragAbsent_executorRequestNeverCallsAdvisors() {
        when(callSpec.chatResponse()).thenReturn(chatResponse("{\"ok\":true,\"result\":\"r\"}"));

        TaskOutcome o = newClient().execute(task, null, ctx(), 0);

        assertThat(o.ok()).isTrue();
        verify(spec, never()).advisors(any(RagAdvisor.class));
    }
}
