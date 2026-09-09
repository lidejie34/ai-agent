package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mcp.connection.McpClientGateway;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import com.dj.ai.agentchat.tool.support.ToolEventStatus;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.dao.QueryTimeoutException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpToolCallback 横切闭环单测（迭代4 T3，AC-18~26/40）：
 * fake {@link McpClientGateway} 脚本化返回/抛错，不启动任何真实 MCP server。
 */
class McpToolCallbackTest {

    private AgentToolCallLogMapper logMapper;
    private ToolAuditService auditService;
    private SecretRedactor redactor;
    private ExecutorService executor;
    private StubGateway gateway;
    private ToolProperties properties;
    private final List<String> markUnavailableReasons = new CopyOnWriteArrayList<>();

    static class StubGateway implements McpClientGateway {
        final AtomicInteger callCount = new AtomicInteger();
        volatile McpSchema.CallToolResult result =
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("stub-mcp-result")), false);
        volatile Throwable toThrow;
        volatile long sleepMs;
        volatile String lastRawName;
        volatile Map<String, Object> lastArgs;

        @Override
        public List<McpSchema.Tool> listTools() {
            throw new UnsupportedOperationException();
        }

        @Override
        public McpSchema.CallToolResult callTool(String rawToolName, Map<String, Object> args) {
            callCount.incrementAndGet();
            lastRawName = rawToolName;
            lastArgs = args;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", e);
                }
            }
            if (toThrow != null) {
                if (toThrow instanceof RuntimeException re) {
                    throw re;
                }
                if (toThrow instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(toThrow);
            }
            return result;
        }

        @Override
        public void onStderr(java.util.function.Consumer<String> lineHandler) {
        }

        @Override
        public void onCrash(Runnable crashHandler) {
        }

        @Override
        public void close() {
        }
    }

    @BeforeEach
    void setUp() {
        logMapper = mock(AgentToolCallLogMapper.class);
        auditService = new ToolAuditService(logMapper);
        redactor = new SecretRedactor(List.of());
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mcp-tool-test-executor");
            t.setDaemon(true);
            return t;
        });
        gateway = new StubGateway();
        properties = new ToolProperties();
        markUnavailableReasons.clear();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private McpSchema.Tool rawTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("path", Map.of("type", "string")),
                List.of("path"),
                false,
                Map.of("inner", Map.of("type", "string")),
                null);
        return new McpSchema.Tool("Read-File", "read file", "读取文件内容", schema, null, null, null);
    }

    private McpToolCallback callback() {
        return new McpToolCallback(rawTool(), "my_fs_read_file", gateway,
                () -> true, markUnavailableReasons::add,
                auditService, redactor, executor, properties);
    }

    private McpToolCallback callback(McpSchema.Tool raw, String exposed, AtomicBoolean alive) {
        return new McpToolCallback(raw, exposed, gateway, alive::get, markUnavailableReasons::add,
                auditService, redactor, executor, properties);
    }

    private ToolContext context(ToolCallBridge bridge, String sessionId, String requestId) {
        Map<String, Object> map = new HashMap<>();
        if (sessionId != null) {
            map.put("sessionId", sessionId);
        }
        map.put("requestId", requestId);
        if (bridge != null) {
            map.put("toolBridge", bridge);
        }
        return new ToolContext(map);
    }

    private AgentToolCallLogPO lastAuditPo() {
        ArgumentCaptor<AgentToolCallLogPO> captor = ArgumentCaptor.forClass(AgentToolCallLogPO.class);
        verify(logMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void toolDefinition_prefixedNameVerbatimDescriptionAndSchemaWithDollarDefs() {
        var def = callback().getToolDefinition();
        assertThat(def.name()).isEqualTo("my_fs_read_file");
        assertThat(def.description()).isEqualTo("读取文件内容");
        assertThat(def.inputSchema()).contains("\"path\"").contains("\"required\"")
                .contains("$defs").contains("inner");
    }

    /** AC-12/18/19：成功路径——raw 名/args 直传网关、结果包 tool-result 无 guide、审计 MCP。 */
    @Test
    void success_invokesGatewayWithRawNameAndArgs_wrapsResult_auditsMcp() {
        String out = callback().call("{\"path\":\"/tmp/a\"}",
                context(new ToolCallBridge(), "sess-1", "req-1"));

        assertThat(out).startsWith("<tool-result>").contains("stub-mcp-result")
                .doesNotContain("<tool-guide");
        assertThat(gateway.callCount.get()).isEqualTo(1);
        assertThat(gateway.lastRawName).isEqualTo("Read-File");
        assertThat(gateway.lastArgs.get("path")).isEqualTo("/tmp/a");

        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getHandlerType()).isEqualTo("MCP");
        assertThat(po.getToolName()).isEqualTo("my_fs_read_file");
        assertThat(po.getStatus()).isEqualTo("SUCCESS");
        assertThat(po.getSessionId()).isEqualTo("sess-1");
        assertThat(po.getCallId()).startsWith("req-1|my_fs_read_file|");
        assertThat(po.getResultChars()).isEqualTo(out.length());
    }

    /** AC-23：同 dedupKey 第二次调用命中缓存——不重复调 server/审计/发帧。 */
    @Test
    void idempotency_secondCallWithSameKey_skipsGatewayAuditAndEvents() {
        ToolCallBridge bridge = new ToolCallBridge();
        List<ToolEvent> events = Collections.synchronizedList(new ArrayList<>());
        bridge.setSink(events::add);
        McpToolCallback cb = callback();
        ToolContext ctx = context(bridge, "sess-1", "req-9");

        String first = cb.call("{\"path\":\"/a\"}", ctx);
        String second = cb.call("{\"path\":\"/a\"}", ctx);

        assertThat(second).isEqualTo(first);
        assertThat(gateway.callCount.get()).isEqualTo(1);
        verify(logMapper, times(1)).insert(any());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).status()).isEqualTo(ToolEventStatus.STARTED);
        assertThat(events.get(1).status()).isEqualTo(ToolEventStatus.SUCCEEDED);
        assertThat(events.get(0).callId()).isEqualTo(events.get(1).callId());
        assertThat(events.get(0).toolName()).isEqualTo("my_fs_read_file");
    }

    /** AC-20：isError=true 是业务失败（协议不抛错）→ MCP_TOOL_ERROR，不摘除 server。 */
    @Test
    void businessError_isErrorTrue_returnsStructuredError_keepsServerAlive() {
        gateway.result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("文件不存在: /no/such")), true);

        String out = callback().call("{\"path\":\"/no/such\"}",
                context(new ToolCallBridge(), null, "req-2"));

        assertThat(out).contains("\"ok\":false").contains("MCP_TOOL_ERROR").contains("文件不存在");
        assertThat(markUnavailableReasons).isEmpty();
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("FAILED");
        assertThat(po.getErrorMessage()).contains("isError=true");
    }

    /** 非文本内容（image/audio/resource）占位省略，不破坏文本回传。 */
    @Test
    void nonTextContent_replacedByPlaceholder() {
        gateway.result = new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("文本部分"),
                new McpSchema.ImageContent(null, "QUJDRA==", "image/png")), false);

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-3"));

        assertThat(out).contains("文本部分").contains("[非文本内容 type=image");
    }

    /** AC-20/10：协议/传输异常全捕获 → MCP_CALL_ERROR + 触发 markUnavailable 摘除。 */
    @Test
    void protocolException_returnsMcpCallError_andMarksUnavailable() {
        gateway.toThrow = new McpError((Object) "MCP connection closed: pipe broken");

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-4"));

        assertThat(out).contains("\"ok\":false").contains("MCP_CALL_ERROR").contains("pipe broken");
        assertThat(markUnavailableReasons).hasSize(1);
        assertThat(markUnavailableReasons.get(0)).contains("pipe broken");
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("FAILED");
    }

    /** AC-40：崩溃降级后工具仍被调用 → MCP_SERVER_UNAVAILABLE，不触达网关。 */
    @Test
    void serverUnavailable_aliveFalse_returnsUnavailableWithoutGatewayCall() {
        McpToolCallback cb = callback(rawTool(), "my_fs_read_file", new AtomicBoolean(false));

        String out = cb.call("{}", context(new ToolCallBridge(), null, "req-5"));

        assertThat(out).contains("\"ok\":false").contains("MCP_SERVER_UNAVAILABLE");
        assertThat(gateway.callCount.get()).isZero();
        assertThat(lastAuditPo().getStatus()).isEqualTo("FAILED");
    }

    /** AC-21：包装层 Future 超时 → TIMEOUT 终态，对话线程不被挂住。 */
    @Test
    void wrapperTimeout_returnsTimeoutResult_andAuditsTimeout() {
        properties.setDefaultTimeoutMs(1000);
        gateway.sleepMs = 3000;

        long start = System.currentTimeMillis();
        String out = callback().call("{}", context(new ToolCallBridge(), "sess-1", "req-6"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2500);
        assertThat(out).contains("\"ok\":false").contains("超时");
        assertThat(lastAuditPo().getStatus()).isEqualTo("TIMEOUT");
    }

    /** R3：SDK reactor 超时（TimeoutException 因果链）识别为 TIMEOUT，且不摘除 server。 */
    @Test
    void sdkTimeout_inCauseChain_recognizedAsTimeoutWithoutMarkUnavailable() {
        gateway.toThrow = new RuntimeException("Did not observe terminal signal",
                new TimeoutException("Did not observe any item within 28000ms"));

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-7"));

        assertThat(out).contains("\"ok\":false").contains("超时");
        assertThat(lastAuditPo().getStatus()).isEqualTo("TIMEOUT");
        assertThat(markUnavailableReasons).isEmpty();
    }

    /** AC-22：结果中的密钥经脱敏后回传模型。 */
    @Test
    void resultSecrets_areRedactedBeforeReturningToModel() {
        gateway.result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("发现 ark-abcdefgh12345678XYZ 泄露")), false);

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-8"));

        assertThat(out).contains(SecretRedactor.REDACTED);
        assertThat(out).doesNotContain("ark-abcdefgh12345678XYZ");
    }

    /** AC-22：结果按 default-output-max-chars 截断并带标记。 */
    @Test
    void oversizedOutput_isTruncatedWithMarker() {
        properties.setDefaultOutputMaxChars(50);
        gateway.result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("X".repeat(500))), false);

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-9"));

        assertThat(out).contains("[输出已截断");
    }

    /** 入参非法 JSON → INVALID_ARGS，不触达网关。 */
    @Test
    void invalidJsonArgs_returnsInvalidArgs_withoutGatewayCall() {
        String out = callback().call("not-a-json", context(new ToolCallBridge(), null, "req-10"));

        assertThat(out).contains("\"ok\":false").contains("INVALID_ARGS");
        assertThat(gateway.callCount.get()).isZero();
        assertThat(lastAuditPo().getStatus()).isEqualTo("FAILED");
    }

    /** AC-37：无状态（null context / 无 sessionId）审计 session_id NULL。 */
    @Test
    void singleArgCall_nullContext_auditsNullSession() {
        String out = callback().call("{\"path\":\"/a\"}");

        assertThat(out).contains("stub-mcp-result");
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getSessionId()).isNull();
        assertThat(po.getCallId()).startsWith("no-request|my_fs_read_file|");
    }

    /** AC-19：审计落库失败不影响对话结果。 */
    @Test
    void auditFailure_doesNotAffectResult() {
        when(logMapper.insert(any())).thenThrow(new QueryTimeoutException("审计库超时"));

        String out = callback().call("{}", context(new ToolCallBridge(), null, "req-11"));

        assertThat(out).contains("stub-mcp-result");
    }

    /** AC-18：started 帧 arguments 已脱敏；终态 failed 帧带脱敏错误。 */
    @Test
    void events_startedArgsRedacted_terminalFailedWithError() {
        ToolCallBridge bridge = new ToolCallBridge();
        List<ToolEvent> events = Collections.synchronizedList(new ArrayList<>());
        bridge.setSink(events::add);
        gateway.toThrow = new RuntimeException("连接断裂 ark-abcdefgh12345678XYZ");

        callback().call("{\"token\":\"ark-abcdefgh12345678XYZ\"}", context(bridge, null, "req-12"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).status()).isEqualTo(ToolEventStatus.STARTED);
        assertThat(events.get(0).arguments()).doesNotContain("ark-abcdefgh12345678XYZ");
        assertThat(events.get(1).status()).isEqualTo(ToolEventStatus.FAILED);
        assertThat(events.get(1).error()).doesNotContain("ark-abcdefgh12345678XYZ");
        assertThat(events.get(1).durationMs()).isNotNull();
    }

    /** AC-24：同步路径 bridge 为 null（无 sink）不报错。 */
    @Test
    void noBridge_doesNotThrow() {
        assertThatCode(() -> callback().call("{}", context(null, null, "req-13")))
                .doesNotThrowAnyException();
        assertThat(gateway.callCount.get()).isEqualTo(1);
    }

    @Test
    void extractText_emptyContent_returnsEmpty() {
        assertThat(McpToolCallback.extractText(
                new McpSchema.CallToolResult(List.of(), false))).isEmpty();
        assertThat(McpToolCallback.extractText(null)).isEmpty();
    }

    @Test
    void hasTimeoutCause_walksCauseChain() {
        assertThat(McpToolCallback.hasTimeoutCause(
                new RuntimeException("x", new TimeoutException("t")))).isTrue();
        assertThat(McpToolCallback.hasTimeoutCause(new TimeoutException("t"))).isTrue();
        assertThat(McpToolCallback.hasTimeoutCause(new RuntimeException("x"))).isFalse();
        assertThat(McpToolCallback.hasTimeoutCause(null)).isFalse();
    }

    @Test
    void inputSchemaJson_nullSchema_defaultsToObject() {
        assertThat(McpToolCallback.toInputSchemaJson(null)).isEqualTo("{\"type\":\"object\"}");
    }
}
