package com.dj.ai.agentchat.orchestration.executor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.orchestration.SddProperties;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.planner.PlannerClient;
import com.dj.ai.agentchat.orchestration.planner.PlannerContext;
import com.dj.ai.agentchat.orchestration.planner.PlannerProtocol;
import com.dj.ai.agentchat.orchestration.planner.SddPrompts;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.orchestration.support.TaskTimeoutException;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.ToolMount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executor 角色模型调用封装（迭代5，T3）：每次执行一个子任务。
 *
 * <p>纪律：每次调用新建 {@code chatClient.prompt()} 规格；<b>唯一挂载共享工具</b>的角色
 * （mount 非空且 callbacks 非空时才 {@code .tools()/.toolContext()}，请求级 ToolContext
 * 携带共享 bridge/run_id）；用户消息仅含原始请求摘要（截断 500 字）+ 子任务标题/目标，
 * 不含主会话历史、不含全部前序结果（AC-28）；同步 {@code .call()} 经 {@link ModelInvoker}
 * 以单任务超时（配置 task-timeout 或总预算剩余）限时。
 *
 * <p>结果契约（AC-29/AC-35/AC-36）：
 * <ul>
 *   <li>{@code {"ok":true,"result":"..."}} → 成功，result 经可选 {@link SecretRedactor} 脱敏
 *       后截断 maxResultChars（默认 2000，附截断标记）回灌 Planner；</li>
 *   <li>{@code {"ok":false,"error":"..."}} → 任务失败，error 脱敏后截断 ≤500 字；</li>
 *   <li>非 JSON / 围栏外散文 / 无 ok 字段 → <b>整段文本视为 result</b>（容错，不判失败）；</li>
 *   <li>超时 → {@link TaskOutcome} timedOut=true（审计 TIMEOUT）；模型异常 → 归一为任务失败
 *       （不抛出，Planner 据此再规划/收尾），审计 FAILED。</li>
 * </ul>
 */
@Slf4j
public class ExecutorClient {

    /** 失败原因（帧 error 字段）最大字符数。 */
    static final int MAX_ERROR_CHARS = 500;
    static final String RESULT_TRUNCATED_MARKER = "…[观察结果已截断]";
    static final String ERROR_TRUNCATED_MARKER = "…[错误信息已截断]";
    static final String TIMEOUT_MESSAGE = "子任务执行超时（已达单任务时限），规划者可改道或收尾";

    private final ChatClient chatClient;
    private final SddProperties props;
    private final ModelInvoker modelInvoker;
    private final OrchestrationAuditService audit;
    /** 可空：工具开关关闭时无 SecretRedactor bean → 仅长度截断兜底（AC-36）。 */
    @Nullable
    private final SecretRedactor redactor;
    /**
     * RAG 常驻 Advisor（迭代6）：app.rag.enabled=false 时 provider 为空/no-bean → 不挂载。
     * 仅 Executor 请求级挂载；Planner/Synth 不感知知识库（F3，避免规划被资料污染）。
     * 可空仅为单测便捷构造。
     */
    @Nullable
    private final ObjectProvider<RagAdvisor> ragAdvisorProvider;

    public ExecutorClient(ChatClient chatClient,
                          SddProperties props,
                          ModelInvoker modelInvoker,
                          OrchestrationAuditService audit,
                          @Nullable SecretRedactor redactor,
                          @Nullable ObjectProvider<RagAdvisor> ragAdvisorProvider) {
        this.chatClient = chatClient;
        this.props = props;
        this.modelInvoker = modelInvoker;
        this.audit = audit;
        this.redactor = redactor;
        this.ragAdvisorProvider = ragAdvisorProvider;
    }

    /**
     * 执行单个子任务；任何模型侧异常都归一为 {@link TaskOutcome#failure}（不抛出），
     * 由编排层驱动 Planner 再规划。
     *
     * @param timeoutNanos 单任务超时（纳秒）；&lt;=0 不额外超时（继承 HTTP 读超时）
     */
    public TaskOutcome execute(TaskSpec task, @Nullable ToolMount mount,
                               PlannerContext ctx, long timeoutNanos) {
        long start = System.nanoTime();
        String userText = SddPrompts.executorUserText(ctx.userText(), task.title(), task.goal());
        try {
            ChatResponse response = modelInvoker.call(() -> {
                // 每次执行新建 spec（红线）
                ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                        .system(PlannerClient.resolveSystemPrompt(
                                props.getExecutor(), SddPrompts.EXECUTOR_DEFAULT_SYSTEM))
                        .messages(List.of(new UserMessage(userText)));
                // Executor 是唯一挂工具的角色：共享挂载、空挂载不改变请求形态
                if (mount != null && mount.callbacks() != null && !mount.callbacks().isEmpty()) {
                    spec.tools(mount.callbacks()).toolContext(mount.toolContext());
                }
                // RAG 同样只在 Executor 请求级挂载：子任务执行可参考知识库，Planner 规划不参考
                RagAdvisor ragAdvisor = ragAdvisorProvider == null
                        ? null : ragAdvisorProvider.getIfAvailable();
                if (ragAdvisor != null) {
                    spec.advisors(ragAdvisor);
                }
                PlannerClient.applyRoleOptions(spec, props.getExecutor());
                return spec.call().chatResponse();
            }, timeoutNanos);
            long durationMs = elapsedMillis(start);
            String model = resolveModel(response);
            ParsedContract parsed = parseContract(extractText(response));
            if (parsed.ok()) {
                String result = sanitizeResult(parsed.text());
                audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                        OrchestrationAuditService.ROLE_EXECUTOR, task.taskId(), task.title(),
                        OrchestrationAuditService.STATUS_SUCCESS, durationMs, model, null);
                return TaskOutcome.success(result, durationMs, model);
            }
            String error = sanitizeError(parsed.text());
            audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                    OrchestrationAuditService.ROLE_EXECUTOR, task.taskId(), task.title(),
                    OrchestrationAuditService.STATUS_FAILED, durationMs, model, error);
            return TaskOutcome.failure(error, durationMs, model, false);
        } catch (TaskTimeoutException e) {
            long durationMs = elapsedMillis(start);
            log.warn("Executor 子任务超时: runId={}, round={}, taskId={}, durationMs={}",
                    ctx.runId(), ctx.round(), task.taskId(), durationMs);
            audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                    OrchestrationAuditService.ROLE_EXECUTOR, task.taskId(), task.title(),
                    OrchestrationAuditService.STATUS_TIMEOUT, durationMs, null, TIMEOUT_MESSAGE);
            return TaskOutcome.failure(TIMEOUT_MESSAGE, durationMs, null, true);
        } catch (RuntimeException e) {
            long durationMs = elapsedMillis(start);
            String error = sanitizeError("模型调用失败: " + e.getMessage());
            log.warn("Executor 子任务模型调用失败（归一为任务失败，交 Planner 再规划）: runId={}, taskId={}, {}",
                    ctx.runId(), task.taskId(), e.getMessage());
            audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                    OrchestrationAuditService.ROLE_EXECUTOR, task.taskId(), task.title(),
                    OrchestrationAuditService.STATUS_FAILED, durationMs, null, error);
            return TaskOutcome.failure(error, durationMs, null, false);
        }
    }

    // ---- 契约解析 ----

    private record ParsedContract(boolean ok, String text) {
    }

    /**
     * 解析 Executor 输出：围栏/散文中的 JSON 优先；{@code ok:false} → 失败；
     * {@code ok:true} 取 result（空 result 回退整段）；非 JSON/无 ok 字段/解析异常 →
     * 整段文本视为成功结果（AC-29 容错）。
     */
    private ParsedContract parseContract(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ParsedContract(false, "执行者未返回内容");
        }
        String json = PlannerProtocol.extractJson(raw);
        if (json == null) {
            return new ParsedContract(true, raw.strip());
        }
        try {
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) {
                return new ParsedContract(true, raw.strip());
            }
            Boolean ok = obj.getBoolean("ok");
            if (ok != null && !ok) {
                String error = obj.getString("error");
                return new ParsedContract(false,
                        StringUtils.hasText(error) ? error : "执行者报告任务失败但未说明原因");
            }
            if (ok != null) {
                String result = obj.getString("result");
                return new ParsedContract(true,
                        StringUtils.hasText(result) ? result : raw.strip());
            }
            return new ParsedContract(true, raw.strip());
        } catch (RuntimeException e) {
            // JSON 形态但解析失败：容错为整段结果
            return new ParsedContract(true, raw.strip());
        }
    }

    // ---- 脱敏与截断 ----

    private String sanitizeResult(String text) {
        String safe = redactor == null ? text : redactor.redact(text);
        int max = Math.max(64, props.getExecutor().getMaxResultChars());
        if (safe != null && safe.length() > max) {
            safe = safe.substring(0, max) + RESULT_TRUNCATED_MARKER;
        }
        return safe;
    }

    private String sanitizeError(String text) {
        if (text == null) {
            return "任务失败（未提供原因）";
        }
        String safe = redactor == null ? text : redactor.redact(text);
        if (safe != null && safe.length() > MAX_ERROR_CHARS) {
            safe = safe.substring(0, MAX_ERROR_CHARS) + ERROR_TRUNCATED_MARKER;
        }
        return safe;
    }

    // ---- 内部 ----

    private static String extractText(ChatResponse response) {
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null) {
            return response.getResult().getOutput().getText();
        }
        return "";
    }

    private String resolveModel(ChatResponse response) {
        if (response != null && response.getMetadata() != null
                && StringUtils.hasText(response.getMetadata().getModel())) {
            return response.getMetadata().getModel();
        }
        String configured = props.getExecutor().getModel();
        return StringUtils.hasText(configured) ? configured : null;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
