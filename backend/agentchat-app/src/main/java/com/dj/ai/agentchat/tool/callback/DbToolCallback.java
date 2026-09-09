package com.dj.ai.agentchat.tool.callback;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.spi.ToolExecutionContext;
import com.dj.ai.agentchat.tool.spi.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.handler.ToolHandlerRouter;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DB 工具注册表驱动的 {@link ToolCallback}（插入迭代 G，核心闭环）。
 *
 * <p>每个装载成功的工具行对应一个实例（持工具行快照：name/description/schema/guide/
 * timeout/outputMaxChars）。M7 框架实际调用<b>双参</b>
 * {@link #call(String, ToolContext)}（默认实现对非空 context 抛
 * UnsupportedOperationException，故必须重写）；单参重载委托双参 + null context。
 *
 * <p>关键机制（详见 full_tech_plan 6.3/6.4/3.5/S2）：
 * <ul>
 *   <li><b>全异常捕获</b>（AC-56）：DefaultToolCallingManager 只兜底 ToolExecutionException，
 *       call() 内 try/catch 兜底所有异常，失败转结构化结果 {@code {"ok":false,...}} 回传模型；</li>
 *   <li><b>超时</b>：handler 执行提交 tool-executor 专用 daemon 池，{@code Future.get(timeoutMs)}
 *       超时 cancel(true) + 结构化 TIMEOUT；</li>
 *   <li><b>脱敏 + 截断</b>：结果文本经 SecretRedactor 后按 output_max_chars 截断（带截断标记）；</li>
 *   <li><b>指南注入</b>（S2）：{@code <tool-guide>} 段前置到每次结果（仅被调用工具的指南进上下文）；</li>
 *   <li><b>幂等</b>（S4）：dedupKey=requestId|toolName|sha1(参数JSON)，bridge 缓存命中直接返回，
 *       不重复执行/审计/发帧；审计表 call_id 唯一索引兜底；</li>
 *   <li><b>审计</b>（AC-37/38）：终态后 best-effort 写 agent_tool_call_log，失败仅 log.error。</li>
 * </ul>
 */
@Slf4j
public class DbToolCallback implements ToolCallback {

    /** 入参摘要（tool 帧 arguments）截断长度。 */
    static final int ARGS_SUMMARY_MAX_CHARS = 500;
    /** 审计 input_summary 列长度上限。 */
    static final int AUDIT_INPUT_MAX_CHARS = 2000;
    /** 审计 error_message 列长度上限。 */
    static final int AUDIT_ERROR_MAX_CHARS = 1000;
    /** timeout_ms 硬上限（管理端校验拦截超限值，此处防御性收口）。 */
    static final int TIMEOUT_HARD_CAP_MS = 60000;

    private final AgentToolPO tool;
    private final ToolHandlerRouter router;
    private final ToolAuditService auditService;
    private final SecretRedactor redactor;
    private final ExecutorService toolExecutor;
    private final int defaultTimeoutMs;
    private final int defaultOutputMaxChars;

    public DbToolCallback(AgentToolPO tool,
                          ToolHandlerRouter router,
                          ToolAuditService auditService,
                          SecretRedactor redactor,
                          ExecutorService toolExecutor,
                          ToolProperties properties) {
        this.tool = tool;
        this.router = router;
        this.auditService = auditService;
        this.redactor = redactor;
        this.toolExecutor = toolExecutor;
        this.defaultTimeoutMs = properties.getDefaultTimeoutMs();
        this.defaultOutputMaxChars = properties.getDefaultOutputMaxChars();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 三字符串逐字来自 DB 行（AC-10）
        return DefaultToolDefinition.builder()
                .name(tool.getToolName())
                .description(tool.getDescription())
                .inputSchema(tool.getInputSchema())
                .build();
    }
    // getToolMetadata() 不覆写：returnDirect=false 默认（结果回模型，不直接回用户）

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        long start = System.currentTimeMillis();
        Map<String, Object> ctxMap = context == null ? Map.of() : context.getContext();
        String sessionId = asString(ctxMap.get("sessionId"));
        String requestId = asString(ctxMap.get("requestId"));
        ToolCallBridge bridge = ctxMap.get("toolBridge") instanceof ToolCallBridge b ? b : null;
        String safeInput = toolInput == null ? "" : toolInput;
        String dedupKey = (requestId == null ? "no-request" : requestId)
                + "|" + tool.getToolName() + "|" + sha1Hex(safeInput);

        // 1) 重试幂等：命中缓存直接返回（不执行/不审计/不发帧；attempt 1 的帧已到达客户端）
        if (bridge != null) {
            ToolCallBridge.CachedOutcome cached = bridge.lookup(dedupKey);
            if (cached != null) {
                log.debug("工具调用命中幂等缓存，跳过执行: toolName={}, callId={}",
                        tool.getToolName(), dedupKey);
                return cached.resultText();
            }
        }

        String argsSummary = truncate(redactor.redact(safeInput), ARGS_SUMMARY_MAX_CHARS);
        publish(bridge, ToolEvent.started(dedupKey, tool.getToolName(), argsSummary));

        // 2) 执行（全捕获，AC-56）
        ToolExecutionResult result = execute(safeInput, sessionId, requestId, start);

        long duration = System.currentTimeMillis() - start;

        // 3) 结果文本：成功为 Markdown；失败为结构化错误 JSON（模型可据指南自我纠正后重调）
        String rawBody = result.ok() ? result.text() : buildErrorJson(result);
        String redacted = redactor.redact(rawBody == null ? "" : rawBody);
        String body = truncate(redacted, effectiveOutputMaxChars());
        String resultText = wrapGuide(body);

        // 4) 审计 best-effort（失败仅日志，不影响对话/结果）
        auditService.record(buildLogPo(dedupKey, sessionId, safeInput, result, duration, resultText));

        // 5) 终态事件 + 幂等缓存
        publish(bridge, ToolEvent.terminal(dedupKey, tool.getToolName(), argsSummary,
                result.ok(), duration,
                result.ok() ? null : truncate(redactor.redact(safeMessage(result.errorMessage())),
                        AUDIT_ERROR_MAX_CHARS)));
        if (bridge != null) {
            bridge.remember(dedupKey, new ToolCallBridge.CachedOutcome(resultText));
        }
        return resultText;
    }

    private ToolExecutionResult execute(String safeInput, String sessionId, String requestId, long start) {
        long timeoutMs = effectiveTimeoutMs();
        Map<String, Object> args;
        try {
            args = parseArgs(safeInput);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failed("INVALID_ARGS", e.getMessage());
        }
        ToolExecutionContext execCtx =
                new ToolExecutionContext(sessionId, requestId, timeoutMs, effectiveOutputMaxChars());
        Future<ToolExecutionResult> future = toolExecutor.submit(
                () -> routeAndExecute(args, execCtx));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时，已取消: toolName={}, timeoutMs={}", tool.getToolName(), timeoutMs);
            return ToolExecutionResult.timeout("工具执行超时（" + timeoutMs + "ms）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolExecutionResult.timeout("工具执行被中断");
        } catch (ExecutionException e) {
            // handler 理论上全捕获；这里兜底任何逃逸异常
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("工具执行异常: toolName={}, {}", tool.getToolName(), cause.getMessage());
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "工具执行异常: " + safeMessage(cause));
        } catch (Throwable t) {
            future.cancel(true);
            log.warn("工具调用兜底异常: toolName={}, {}", tool.getToolName(), t.getMessage());
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "工具执行异常: " + safeMessage(t));
        }
    }

    private ToolExecutionResult routeAndExecute(Map<String, Object> args, ToolExecutionContext execCtx) {
        try {
            HandlerType type = HandlerType.valueOf(
                    tool.getHandlerType().trim().toUpperCase(Locale.ROOT));
            ToolHandler handler = router.route(type);
            return handler.execute(tool, args, execCtx);
        } catch (SkippableToolException e) {
            // 装载后处理器失效（如脚本被删）的防御路径
            return ToolExecutionResult.failed("TOOL_NOT_AVAILABLE", "工具不可用: " + e.getMessage());
        } catch (Throwable t) {
            if (Thread.currentThread().isInterrupted()) {
                return ToolExecutionResult.timeout("工具执行被中断");
            }
            // 处理器承诺全捕获；此为最终兜底，绝不让 RuntimeException 上浮致整轮对话失败（AC-56）
            log.error("工具处理器逃逸异常已兜底: toolName={}", tool.getToolName(), t);
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "工具执行异常: " + safeMessage(t));
        }
    }

    private Map<String, Object> parseArgs(String input) {
        if (!StringUtils.hasText(input)) {
            return Map.of();
        }
        try {
            JSONObject obj = JSON.parseObject(input);
            return obj == null ? Map.of() : obj;
        } catch (Exception e) {
            throw new IllegalArgumentException("工具入参不是合法 JSON 对象: " + e.getMessage());
        }
    }

    private String buildErrorJson(ToolExecutionResult result) {
        JSONObject json = new JSONObject();
        json.put("ok", false);
        json.put("errorCode", result.errorCode());
        json.put("error", result.errorMessage());
        if (StringUtils.hasText(result.text())) {
            json.put("detail", truncate(result.text(), 1000));
        }
        return json.toJSONString();
    }

    /**
     * 指南注入（S2）：guide_md 全文作为 {@code <tool-guide>} 段前置；
     * 结果体包 {@code <tool-result>}。guide 为 null 时只包 result。
     */
    private String wrapGuide(String body) {
        String resultBlock = "<tool-result>\n" + body + "\n</tool-result>";
        String guide = tool.getGuideMd();
        if (!StringUtils.hasText(guide)) {
            return resultBlock;
        }
        return "<tool-guide name=\"" + tool.getToolName() + "\">\n"
                + guide + "\n</tool-guide>\n" + resultBlock;
    }

    private AgentToolCallLogPO buildLogPo(String dedupKey, String sessionId, String toolInput,
                                          ToolExecutionResult result, long duration, String resultText) {
        AgentToolCallLogPO po = new AgentToolCallLogPO();
        po.setCallId(dedupKey);
        po.setToolName(tool.getToolName());
        po.setHandlerType(tool.getHandlerType());
        po.setSessionId(sessionId);
        po.setInputSummary(truncate(redactor.redact(toolInput), AUDIT_INPUT_MAX_CHARS));
        po.setStatus(result.status());
        po.setDurationMs(duration);
        po.setErrorMessage(result.ok() ? null
                : truncate(redactor.redact(safeMessage(result.errorMessage())), AUDIT_ERROR_MAX_CHARS));
        po.setResultChars(resultText.length());
        return po;
    }

    private long effectiveTimeoutMs() {
        Integer row = tool.getTimeoutMs();
        long value = row != null && row > 0 ? row : defaultTimeoutMs;
        return Math.min(value, TIMEOUT_HARD_CAP_MS);
    }

    private int effectiveOutputMaxChars() {
        Integer row = tool.getOutputMaxChars();
        return row != null && row > 0 ? row : defaultOutputMaxChars;
    }

    private static void publish(ToolCallBridge bridge, ToolEvent event) {
        if (bridge != null) {
            bridge.publish(event);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeMessage(String message) {
        return StringUtils.hasText(message) ? message : "未知错误";
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return StringUtils.hasText(message) ? message : t.getClass().getSimpleName();
    }

    /** 截断并追加截断标记；null 安全。 */
    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
                + "\n...[输出已截断，原始长度 " + text.length() + " 字符]";
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-1 为 JDK 内置算法，不可能缺失；兜底用 hashCode 保持键非空
            return "hash-" + input.hashCode();
        }
    }
}
