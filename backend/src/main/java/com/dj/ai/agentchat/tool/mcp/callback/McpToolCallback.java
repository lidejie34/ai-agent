package com.dj.ai.agentchat.tool.mcp.callback;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.mcp.connection.McpClientGateway;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.DefaultToolSupport;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * MCP 工具的 {@link ToolCallback} 包装（插入迭代4，T3，FR-4）：把一个 READY server
 * 发现的 raw {@link McpSchema.Tool} 包装为模型可调用回调，<b>完整复刻 DbToolCallback
 * 横切语义</b>（双参 call 入口 / 全异常捕获 / Future 超时 / 脱敏截断 / 幂等 / event:tool
 * 帧 / best-effort 审计），差异仅在：
 * <ul>
 *   <li>工具名为 server 命名空间前缀形式（{@link McpToolNames}）；</li>
 *   <li>无 guide_md 注入（AC-25），结果仅包 {@code <tool-result>}；</li>
 *   <li>执行体为 {@link McpClientGateway#callTool}（MCP 协议），TextContent 提取文本，
 *       非文本内容占位省略；{@code isError=true} 为业务失败 MCP_TOOL_ERROR（协议不抛错）；</li>
 *   <li>连接已断裂（崩溃降级后）→ MCP_SERVER_UNAVAILABLE 结构化失败（AC-40）；</li>
 *   <li>协议/传输异常 → MCP_CALL_ERROR 并触发 markUnavailable 摘除（进程/连接已不可信，
 *       D11 不重启）；SDK 协议超时（reactor TimeoutException 因果链）与包装层超时统一
 *       识别为 TIMEOUT 终态（R3）；</li>
 *   <li>审计 handlerType 字面量 {@code "MCP"}，tool_name 为带前缀全名（列宽 ≥128，不截断）。</li>
 * </ul>
 */
@Slf4j
public class McpToolCallback implements ToolCallback {

    /** 审计 handler_type 字面量（HandlerType 枚举不变，MCP 非 DB 工具行类型）。 */
    public static final String HANDLER_TYPE_MCP = "MCP";

    static final int ARGS_SUMMARY_MAX_CHARS = 500;
    static final int AUDIT_INPUT_MAX_CHARS = 2000;
    static final int AUDIT_ERROR_MAX_CHARS = 1000;
    static final int TIMEOUT_HARD_CAP_MS = 60000;

    private final McpSchema.Tool rawTool;
    private final String exposedName;
    private final McpClientGateway gateway;
    private final BooleanSupplier aliveCheck;
    private final Consumer<String> markUnavailable;
    private final ToolAuditService auditService;
    private final SecretRedactor redactor;
    private final ExecutorService toolExecutor;
    private final int defaultTimeoutMs;
    private final int defaultOutputMaxChars;

    public McpToolCallback(McpSchema.Tool rawTool,
                           String exposedName,
                           McpClientGateway gateway,
                           BooleanSupplier aliveCheck,
                           Consumer<String> markUnavailable,
                           ToolAuditService auditService,
                           SecretRedactor redactor,
                           ExecutorService toolExecutor,
                           ToolProperties properties) {
        this.rawTool = rawTool;
        this.exposedName = exposedName;
        this.gateway = gateway;
        this.aliveCheck = aliveCheck;
        this.markUnavailable = markUnavailable;
        this.auditService = auditService;
        this.redactor = redactor;
        this.toolExecutor = toolExecutor;
        this.defaultTimeoutMs = properties.getDefaultTimeoutMs();
        this.defaultOutputMaxChars = properties.getDefaultOutputMaxChars();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 描述/入参 Schema 逐字来自 server 发现结果（AC-12）
        String description = StringUtils.hasText(rawTool.description())
                ? rawTool.description() : rawTool.name();
        return DefaultToolDefinition.builder()
                .name(exposedName)
                .description(description)
                .inputSchema(toInputSchemaJson(rawTool.inputSchema()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        long start = System.currentTimeMillis();
        Map<String, Object> ctxMap = context == null ? Map.of() : context.getContext();
        String sessionId = asString(ctxMap.get(DefaultToolSupport.CTX_SESSION_ID));
        String requestId = asString(ctxMap.get(DefaultToolSupport.CTX_REQUEST_ID));
        ToolCallBridge bridge = ctxMap.get(DefaultToolSupport.CTX_TOOL_BRIDGE) instanceof ToolCallBridge b
                ? b : null;
        String safeInput = toolInput == null ? "" : toolInput;
        String dedupKey = (requestId == null ? "no-request" : requestId)
                + "|" + exposedName + "|" + sha1Hex(safeInput);

        // 1) 重试幂等：命中缓存直接返回（不执行/不审计/不发帧，AC-23）
        if (bridge != null) {
            ToolCallBridge.CachedOutcome cached = bridge.lookup(dedupKey);
            if (cached != null) {
                log.debug("MCP 工具调用命中幂等缓存，跳过执行: toolName={}, callId={}", exposedName, dedupKey);
                return cached.resultText();
            }
        }

        String argsSummary = truncate(redactor.redact(safeInput), ARGS_SUMMARY_MAX_CHARS);
        publish(bridge, ToolEvent.started(dedupKey, exposedName, argsSummary));

        // 2) 执行（全捕获，AC-20）
        ToolExecutionResult result = execute(safeInput, start);

        long duration = System.currentTimeMillis() - start;

        // 3) 结果文本：成功为 server 文本；失败为结构化错误 JSON；无 guide（AC-25）
        String rawBody = result.ok() ? result.text() : buildErrorJson(result);
        String redacted = redactor.redact(rawBody == null ? "" : rawBody);
        String body = truncate(redacted, defaultOutputMaxChars);
        String resultText = "<tool-result>\n" + body + "\n</tool-result>";

        // 4) 审计 best-effort（handlerType=MCP，tool_name 为带前缀全名，AC-19）
        auditService.record(buildLogPo(dedupKey, sessionId, safeInput, result, duration, resultText));

        // 5) 终态事件 + 幂等缓存
        publish(bridge, ToolEvent.terminal(dedupKey, exposedName, argsSummary,
                result.ok(), duration,
                result.ok() ? null : truncate(redactor.redact(safeMessage(result.errorMessage())),
                        AUDIT_ERROR_MAX_CHARS)));
        if (bridge != null) {
            bridge.remember(dedupKey, new ToolCallBridge.CachedOutcome(resultText));
        }
        return resultText;
    }

    private ToolExecutionResult execute(String safeInput, long start) {
        long timeoutMs = effectiveTimeoutMs();
        Map<String, Object> args;
        try {
            args = parseArgs(safeInput);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failed("INVALID_ARGS", e.getMessage());
        }
        // 崩溃降级后工具仍被模型调用（工具已挂载但进程已死，AC-40）
        if (!aliveCheck.getAsBoolean()) {
            return ToolExecutionResult.failed("MCP_SERVER_UNAVAILABLE",
                    "MCP server 当前不可用（连接未建立或已崩溃），请稍后重试或联系管理员");
        }
        Future<ToolExecutionResult> future = toolExecutor.submit(() -> invoke(args));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("MCP 工具执行超时，已取消: toolName={}, timeoutMs={}", exposedName, timeoutMs);
            return ToolExecutionResult.timeout("MCP 工具执行超时（" + timeoutMs + "ms）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolExecutionResult.timeout("MCP 工具执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (hasTimeoutCause(cause)) {
                future.cancel(true);
                log.warn("MCP 协议层超时，按 TIMEOUT 终态处理: toolName={}", exposedName);
                return ToolExecutionResult.timeout("MCP 工具执行超时（" + timeoutMs + "ms）");
            }
            // invoke 内部已全捕获；此处为最终兜底
            log.warn("MCP 工具执行异常: toolName={}, {}", exposedName, cause.getMessage());
            return ToolExecutionResult.failed("MCP_CALL_ERROR", "MCP 调用失败: " + safeMessage(cause));
        } catch (Throwable t) {
            future.cancel(true);
            log.warn("MCP 工具调用兜底异常: toolName={}, {}", exposedName, t.getMessage());
            return ToolExecutionResult.failed("MCP_CALL_ERROR", "MCP 调用失败: " + safeMessage(t));
        }
    }

    /** 真实协议调用（运行在 tool-executor 隔离线程，AC-26）；全捕获不抛。 */
    private ToolExecutionResult invoke(Map<String, Object> args) {
        try {
            McpSchema.CallToolResult callResult = gateway.callTool(rawTool.name(), args);
            String text = extractText(callResult);
            boolean businessError = callResult != null && Boolean.TRUE.equals(callResult.isError());
            if (businessError) {
                // MCP 业务失败不抛异常（isError=true），文本即 server 给出的错误说明
                log.info("MCP server 返回业务错误 isError=true: toolName={}", exposedName);
                return ToolExecutionResult.failed("MCP_TOOL_ERROR", "MCP 工具返回错误（isError=true）",
                        StringUtils.hasText(text) ? truncate(text, AUDIT_ERROR_MAX_CHARS) : null);
            }
            return ToolExecutionResult.success(text);
        } catch (Throwable t) {
            if (Thread.currentThread().isInterrupted()) {
                return ToolExecutionResult.timeout("MCP 工具执行被中断");
            }
            if (hasTimeoutCause(t)) {
                log.warn("MCP 协议层超时，按 TIMEOUT 终态处理: toolName={}", exposedName);
                return ToolExecutionResult.timeout("MCP 工具执行超时（SDK requestTimeout）");
            }
            // 协议/传输/进程级失败：连接已不可信，摘除该 server 全部工具（不重启，D11）
            String reason = safeMessage(t);
            log.warn("MCP 调用异常，触发 server 摘除: toolName={}, 原因={}", exposedName, reason);
            try {
                markUnavailable.accept(redactor.redact(reason));
            } catch (Throwable suppressed) {
                log.warn("markUnavailable 回调异常（不影响错误返回）: {}", suppressed.getMessage());
            }
            return ToolExecutionResult.failed("MCP_CALL_ERROR", "MCP 调用失败: " + safeMessage(t));
        }
    }

    /** TextContent 拼接为模型可读文本；非文本内容（image/audio/resource）占位省略。 */
    static String extractText(McpSchema.CallToolResult callResult) {
        List<McpSchema.Content> contents = callResult == null ? null : callResult.content();
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (textContent.text() != null) {
                    parts.add(textContent.text());
                }
            } else {
                parts.add("[非文本内容 type=" + content.type() + "，已省略]");
            }
        }
        return String.join("\n", parts);
    }

    /** SDK reactor .timeout 以 java.util.concurrent.TimeoutException 出现在因果链上（R3）。 */
    static boolean hasTimeoutCause(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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

    /** JsonSchema record → JSON 字符串（组件名 defs 对应 JSON 键 $defs，手工映射避免误序列化）。 */
    static String toInputSchemaJson(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return "{\"type\":\"object\"}";
        }
        JSONObject json = new JSONObject();
        if (schema.type() != null) {
            json.put("type", schema.type());
        }
        if (schema.properties() != null) {
            json.put("properties", schema.properties());
        }
        if (schema.required() != null) {
            json.put("required", schema.required());
        }
        if (schema.additionalProperties() != null) {
            json.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.defs() != null) {
            json.put("$defs", schema.defs());
        }
        if (schema.definitions() != null) {
            json.put("definitions", schema.definitions());
        }
        if (json.isEmpty()) {
            return "{\"type\":\"object\"}";
        }
        if (!json.containsKey("type")) {
            // MCP 入参 Schema 惯例为 object；缺类型时补齐，避免部分模型端校验报错
            json.put("type", "object");
        }
        return json.toJSONString();
    }

    private AgentToolCallLogPO buildLogPo(String dedupKey, String sessionId, String toolInput,
                                          ToolExecutionResult result, long duration, String resultText) {
        AgentToolCallLogPO po = new AgentToolCallLogPO();
        po.setCallId(dedupKey);
        po.setToolName(exposedName);
        po.setHandlerType(HANDLER_TYPE_MCP);
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
        return Math.min(Math.max(1000, defaultTimeoutMs), TIMEOUT_HARD_CAP_MS);
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
            return "hash-" + input.hashCode();
        }
    }
}
