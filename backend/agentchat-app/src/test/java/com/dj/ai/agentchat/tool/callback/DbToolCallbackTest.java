package com.dj.ai.agentchat.tool.callback;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.spi.ToolExecutionContext;
import com.dj.ai.agentchat.tool.spi.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.handler.ToolHandlerRouter;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import com.dj.ai.agentchat.tool.support.ToolEventStatus;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4：DbToolCallback 核心闭环——双参 call 不抛 UnsupportedOperationException、
 * 全异常捕获结构化回传、Future 超时、脱敏、截断、guide 前置进 description、幂等去重、
 * 事件顺序、审计字段与 best-effort。
 */
class DbToolCallbackTest {

    private AgentToolCallLogMapper logMapper;
    private ToolAuditService auditService;
    private SecretRedactor redactor;
    private ExecutorService executor;
    private StubHandler handler;
    private ToolHandlerRouter router;
    private AgentToolPO tool;
    private ToolProperties properties;

    static class StubHandler implements ToolHandler {
        final AtomicInteger invocations = new AtomicInteger(0);
        volatile ToolExecutionResult result = ToolExecutionResult.success("stub-result");
        volatile RuntimeException toThrow = null;
        volatile long sleepMs = 0;
        volatile Map<String, Object> lastArgs;
        volatile ToolExecutionContext lastCtx;

        @Override
        public HandlerType type() {
            return HandlerType.BUILTIN;
        }

        @Override
        public void validateConfig(String handlerConfigJson) {
        }

        @Override
        public ToolExecutionResult execute(AgentToolPO po, Map<String, Object> args,
                                           ToolExecutionContext ctx) {
            invocations.incrementAndGet();
            lastArgs = args;
            lastCtx = ctx;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", e);
                }
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    @BeforeEach
    void setUp() {
        logMapper = mock(AgentToolCallLogMapper.class);
        auditService = new ToolAuditService(logMapper);
        redactor = new SecretRedactor(List.of());
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "tool-test-executor");
            t.setDaemon(true);
            return t;
        });
        handler = new StubHandler();
        router = mock(ToolHandlerRouter.class);
        when(router.route(HandlerType.BUILTIN)).thenReturn(handler);
        properties = new ToolProperties();

        tool = new AgentToolPO();
        tool.setId(1L);
        tool.setToolName("demo_builtin_tool");
        tool.setDescription("分析日志错误");
        tool.setInputSchema("{\"type\":\"object\"}");
        tool.setHandlerType("BUILTIN");
        tool.setHandlerConfig("{\"bean\":\"demoBeanKey\"}");
        tool.setGuideMd("# 指南标题\n指南正文内容");
        tool.setEnabled(true);
        tool.setTimeoutMs(5000);
        tool.setOutputMaxChars(8000);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private DbToolCallback callback() {
        return new DbToolCallback(tool, router, auditService, redactor, executor, properties);
    }

    private ToolContext context(ToolCallBridge bridge, String sessionId, String requestId) {
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", sessionId);
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
    void toolDefinition_guidePrefixedIntoDescription() {
        var def = callback().getToolDefinition();
        assertThat(def.name()).isEqualTo("demo_builtin_tool");
        assertThat(def.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        // 指南前置（S2 修订）：description = DB 描述 + 全文 guide，随 tools 数组
        // 在模型决定是否/如何调用之前可见
        assertThat(def.description()).isEqualTo("分析日志错误\n\n# 指南标题\n指南正文内容");
    }

    @Test
    void toolDefinition_guideNull_descriptionVerbatimFromDbRow() {
        tool.setGuideMd(null);
        var def = callback().getToolDefinition();
        assertThat(def.description()).isEqualTo("分析日志错误");
    }

    @Test
    void doubleArgCall_executesHandlerAndWrapsResultOnly() {
        handler.result = ToolExecutionResult.success("## 分析结果\nERROR=0");

        String out = callback().call("{\"minutes\":30}", context(new ToolCallBridge(), "sess-1", "req-1"));

        // 指南已前置进 ToolDefinition.description，结果只包 <tool-result>，不再重复指南
        assertThat(out).doesNotContain("<tool-guide").doesNotContain("# 指南标题");
        assertThat(out).startsWith("<tool-result>")
                .contains("## 分析结果\nERROR=0");
        // 双参 call 被框架路径调用，handler 确实执行（非 UnsupportedOperationException 路径）
        assertThat(handler.invocations.get()).isEqualTo(1);
        assertThat(handler.lastArgs.get("minutes")).isEqualTo(30);
        assertThat(handler.lastCtx.sessionId()).isEqualTo("sess-1");
        assertThat(handler.lastCtx.requestId()).isEqualTo("req-1");

        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("SUCCESS");
        assertThat(po.getToolName()).isEqualTo("demo_builtin_tool");
        assertThat(po.getSessionId()).isEqualTo("sess-1");
        assertThat(po.getCallId()).startsWith("req-1|demo_builtin_tool|");
        assertThat(po.getResultChars()).isEqualTo(out.length());
        assertThat(po.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void handlerThrowing_returnsStructuredError_andDoesNotEscape() {
        handler.toThrow = new RuntimeException("boom-stack-npe");

        assertThatCode(() -> callback().call("{}", context(new ToolCallBridge(), null, "req-2")))
                .doesNotThrowAnyException();

        String result = callback().call("{}", context(new ToolCallBridge(), null, "req-2"));
        assertThat(result).contains("\"ok\":false").contains("boom-stack-npe");
    }

    @Test
    void handlerThrowing_auditsFailed_andNeverEscapes() {
        handler.toThrow = new IllegalStateException("模拟处理器逃逸");

        String result = callback().call("{}", context(new ToolCallBridge(), "sess-1", "req-3"));

        assertThat(result).contains("\"ok\":false").contains("模拟处理器逃逸");
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("FAILED");
        assertThat(po.getErrorMessage()).contains("模拟处理器逃逸");
        assertThat(po.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void timeout_returnsTimeoutResult_andAuditsTimeout() {
        tool.setTimeoutMs(100);
        handler.sleepMs = 2000;

        long start = System.currentTimeMillis();
        String result = callback().call("{}", context(new ToolCallBridge(), "sess-1", "req-4"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(1500);
        assertThat(result).contains("\"ok\":false").contains("超时");
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    void resultSecrets_areRedactedBeforeReturningToModel() {
        handler.result = ToolExecutionResult.success("日志里发现 ark-abcdefgh12345678XYZ 泄露");

        String result = callback().call("{}", context(new ToolCallBridge(), null, "req-5"));

        assertThat(result).contains(SecretRedactor.REDACTED);
        assertThat(result).doesNotContain("ark-abcdefgh12345678XYZ");
    }

    @Test
    void oversizedOutput_isTruncatedWithMarker() {
        tool.setOutputMaxChars(100);
        handler.result = ToolExecutionResult.success("X".repeat(500));

        String result = callback().call("{}", context(new ToolCallBridge(), null, "req-6"));

        assertThat(result).contains("[输出已截断");
        // <tool-result> 体内正文不超过上限 + 截断标记
        assertThat(result).contains("X".repeat(100));
    }

    @Test
    void result_alwaysWrappedInToolResultEnvelope() {
        String result = callback().call("{}", context(new ToolCallBridge(), null, "req-7"));

        assertThat(result).doesNotContain("<tool-guide");
        assertThat(result).startsWith("<tool-result>");
    }

    @Test
    void auditFailure_doesNotAffectResult() {
        when(logMapper.insert(any())).thenThrow(new QueryTimeoutException("审计库超时"));

        String result = callback().call("{}", context(new ToolCallBridge(), null, "req-8"));

        assertThat(result).contains("stub-result");
    }

    @Test
    void bridgeIdempotency_secondCallWithSameKey_skipsExecuteAuditAndEvents() {
        ToolCallBridge bridge = new ToolCallBridge();
        List<ToolEvent> events = Collections.synchronizedList(new ArrayList<>());
        bridge.setSink(events::add);
        DbToolCallback cb = callback();
        ToolContext ctx = context(bridge, "sess-1", "req-9");

        String first = cb.call("{\"minutes\":30}", ctx);
        String second = cb.call("{\"minutes\":30}", ctx);

        assertThat(second).isEqualTo(first);
        assertThat(handler.invocations.get()).isEqualTo(1); // 只执行一次
        verify(logMapper, times(1)).insert(any());         // 只审计一次
        assertThat(events).hasSize(2);                     // started + terminal
        assertThat(events.get(0).status()).isEqualTo(ToolEventStatus.STARTED);
        assertThat(events.get(1).status()).isEqualTo(ToolEventStatus.SUCCEEDED);
        assertThat(events.get(1).durationMs()).isNotNull();
        assertThat(events.get(0).callId()).isEqualTo(events.get(1).callId());
    }

    @Test
    void failedCall_publishesFailedTerminalEvent() {
        ToolCallBridge bridge = new ToolCallBridge();
        List<ToolEvent> events = Collections.synchronizedList(new ArrayList<>());
        bridge.setSink(events::add);
        handler.toThrow = new RuntimeException("失败原因");

        callback().call("{}", context(bridge, null, "req-10"));

        assertThat(events).hasSize(2);
        assertThat(events.get(1).status()).isEqualTo(ToolEventStatus.FAILED);
        assertThat(events.get(1).error()).contains("失败原因");
    }

    @Test
    void singleArgCall_nullContext_worksAndAuditsNullSession() {
        String result = callback().call("{\"minutes\":10}");

        assertThat(result).contains("stub-result");
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getSessionId()).isNull();
        assertThat(po.getCallId()).startsWith("no-request|demo_builtin_tool|");
    }

    @Test
    void invalidJsonArgs_returnsInvalidArgs_withoutExecutingHandler() {
        String result = callback().call("not-a-json", context(new ToolCallBridge(), null, "req-11"));

        assertThat(result).contains("\"ok\":false").contains("INVALID_ARGS");
        assertThat(handler.invocations.get()).isZero();
        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void auditInputSummary_isRedacted() {
        callback().call("{\"token\":\"ark-abcdefgh12345678XYZ\"}",
                context(new ToolCallBridge(), null, "req-12"));

        AgentToolCallLogPO po = lastAuditPo();
        assertThat(po.getInputSummary()).contains(SecretRedactor.REDACTED);
        assertThat(po.getInputSummary()).doesNotContain("ark-abcdefgh12345678XYZ");
    }
}
