package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.mcp.McpProperties;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolCallbackFactory;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * McpServerConnectionManager 单测（迭代4 T2，AC-6/7/8/9/10/11/35）：
 * 全部使用 fake {@link McpClientFactory}/{@link McpClientGateway}——不启动任何真实 MCP 子进程
 * （真实 npx server 属 step_8 冒烟）。
 */
class McpServerConnectionManagerTest {

    // ==================== 正常路径 ====================

    /** AC-6：多 server 并行启动全部 READY，发现工具数/连接视图/回调快照正确。 */
    @Test
    void startup_multipleServersReady_snapshotsAndViews() {
        FakeGateway gA = new FakeGateway("fs").tools(tool("read_file"), tool("write_file"));
        FakeGateway gB = new FakeGateway("ev").tools(tool("echo"));
        FakeFactory factory = new FakeFactory(Map.of("fs", gA, "ev", gB));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of(spec("fs", "/bin/fs"), spec("ev", "/bin/ev")));

        manager.startup();

        assertThat(manager.connections()).hasSize(2);
        McpServerConnection a = manager.connections().get(0);
        McpServerConnection b = manager.connections().get(1);
        assertThat(a.name()).isEqualTo("fs");
        assertThat(a.isReady()).isTrue();
        assertThat(a.connectedAt()).isNotNull();
        assertThat(a.tools()).hasSize(2);
        assertThat(b.isReady()).isTrue();
        assertThat(b.tools()).hasSize(1);
        // 每个 READY server 经回调工厂产出 1 个 stub 回调
        assertThat(manager.toolCallbacks()).hasSize(2);
        assertThat(factory.connectCount.get()).isEqualTo(2);
        // stderr/崩溃回调均已注册
        assertThat(gA.stderrHandler).isNotNull();
        assertThat(gA.crashHandler).isNotNull();
        assertThat(gB.stderrHandler).isNotNull();
        assertThat(gB.crashHandler).isNotNull();
        // R3：SDK 请求超时 = 30s 默认执行超时 - 2s = 28s；初始化超时 = mcp.request-timeout 20s
        assertThat(factory.capturedRequestTimeout).isEqualTo(Duration.ofMillis(28000));
        assertThat(factory.capturedInitTimeout).isEqualTo(Duration.ofSeconds(20));
    }

    // ==================== 失败隔离 ====================

    /** AC-7：握手抛错逐 server 隔离 UNAVAILABLE+WARN，不阻断启动/其他 server。 */
    @Test
    void startup_handshakeFailure_isolatedUnavailable() {
        FakeGateway gA = new FakeGateway("ok").tools(tool("echo"));
        FakeFactory factory = new FakeFactory(Map.of("ok", gA))
                .connectError("bad", new McpConnectException("MCP server 握手失败: bad: 启动即退", null));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of(spec("ok", "/bin/ok"), spec("bad", "/bin/bad")));

        assertThatCode(manager::startup).doesNotThrowAnyException();

        assertThat(manager.connections()).hasSize(2);
        McpServerConnection ok = manager.connections().get(0);
        McpServerConnection bad = manager.connections().get(1);
        assertThat(ok.isReady()).isTrue();
        assertThat(bad.isReady()).isFalse();
        assertThat(bad.status()).isEqualTo(McpServerStatus.UNAVAILABLE);
        assertThat(bad.gateway()).isNull();
        assertThat(bad.lastError()).contains("握手失败");
        assertThat(bad.connectedAt()).isNull();
        // UNAVAILABLE server 不产出回调
        assertThat(manager.toolCallbacks()).hasSize(1);
    }

    /** 握手成功但 listTools 抛错：半启动网关必须被 close 回收（R7/AC-9），其余 server 不受影响。 */
    @Test
    void startup_listToolsThrows_halfStartedGatewayClosed() {
        FakeGateway gA = new FakeGateway("ok").tools(tool("echo"));
        FakeGateway gBroken = new FakeGateway("broken").listToolsError(new RuntimeException("listTools boom"));
        FakeFactory factory = new FakeFactory(Map.of("ok", gA, "broken", gBroken));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of(spec("ok", "/bin/ok"), spec("broken", "/bin/broken")));

        manager.startup();

        assertThat(manager.connections().get(0).isReady()).isTrue();
        McpServerConnection broken = manager.connections().get(1);
        assertThat(broken.isReady()).isFalse();
        assertThat(broken.lastError()).contains("listTools boom");
        assertThat(gBroken.closed).isTrue();
        assertThat(manager.toolCallbacks()).hasSize(1);
    }

    /** AC-4/配置校验：非法 name / 空 command / filesystem 无目录 → 跳过连接，不调用工厂。 */
    @Test
    void startup_invalidSpecs_skippedWithoutConnecting() {
        FakeFactory factory = new FakeFactory(Map.of());
        McpProperties.ServerSpec badName = spec("Bad_Name", "/bin/x");
        McpProperties.ServerSpec blankCmd = spec("blank", "   ");
        McpProperties.ServerSpec fsNoDir = spec("fs", "npx");
        fsNoDir.setArgs(new ArrayList<>(List.of("-y", "@modelcontextprotocol/server-filesystem")));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of(badName, blankCmd, fsNoDir));

        manager.startup();

        assertThat(factory.connectCount.get()).isZero();
        assertThat(manager.connections()).hasSize(3);
        for (McpServerConnection conn : manager.connections()) {
            assertThat(conn.isReady()).isFalse();
            assertThat(conn.status()).isEqualTo(McpServerStatus.UNAVAILABLE);
            assertThat(conn.lastError()).contains("配置非法");
        }
        assertThat(manager.toolCallbacks()).isEmpty();
    }

    /** 无 server 配置：空快照，不抛错。 */
    @Test
    void startup_emptyServers_emptySnapshots() {
        FakeFactory factory = new FakeFactory(Map.of());
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of());

        assertThatCode(manager::startup).doesNotThrowAnyException();

        assertThat(manager.connections()).isEmpty();
        assertThat(manager.toolCallbacks()).isEmpty();
    }

    // ==================== 崩溃降级 ====================

    /** AC-10/D11：运行期崩溃信号 → markUnavailable 摘除回调，不自动重启；其他 server 不受影响。 */
    @Test
    void crash_marksUnavailable_removesCallbacks_noRestart() {
        FakeGateway gA = new FakeGateway("keep").tools(tool("echo"));
        FakeGateway gB = new FakeGateway("dead").tools(tool("echo2"));
        FakeFactory factory = new FakeFactory(Map.of("keep", gA, "dead", gB));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(), List.of(spec("keep", "/bin/keep"), spec("dead", "/bin/dead")));
        manager.startup();
        assertThat(manager.toolCallbacks()).hasSize(2);

        gB.fireCrash();

        assertThat(manager.connections().get(1).isReady()).isFalse();
        assertThat(manager.connections().get(1).lastError()).contains("进程退出");
        assertThat(manager.connections().get(0).isReady()).isTrue();
        assertThat(manager.toolCallbacks()).hasSize(1);
        // 不自动重启：工厂连接次数不变
        assertThat(factory.connectCount.get()).isEqualTo(2);
        // 幂等：重复崩溃信号不抛错
        assertThatCode(gB::fireCrash).doesNotThrowAnyException();
        assertThat(manager.toolCallbacks()).hasSize(1);
    }

    // ==================== stderr 脱敏消费 ====================

    /** AC-11：stderr 行经 redactor 脱敏后落日志；消费链路不抛错。 */
    @Test
    void stderr_linePassedThroughRedactor() {
        FakeGateway gA = new FakeGateway("srv").tools(tool("echo"));
        RecordingRedactor redactor = new RecordingRedactor();
        FakeFactory factory = new FakeFactory(Map.of("srv", gA));
        McpServerConnectionManager manager = newManager(factory, redactor,
                new ToolProperties(), List.of(spec("srv", "/bin/srv")));
        manager.startup();

        assertThatCode(() -> gA.emitStderr("Bearer ark-secret-1234567890")).doesNotThrowAnyException();

        assertThat(redactor.calls).anyMatch(s -> s.contains("ark-secret-1234567890"));
    }

    // ==================== 关闭回收 ====================

    /** AC-9：@PreDestroy 关闭全部网关；单个 close 抛错不影响其余；UNAVAILABLE（无网关）不碍事。 */
    @Test
    void shutdown_closesAllGateways_swallowsCloseThrow() {
        FakeGateway gA = new FakeGateway("aa").tools(tool("t1")).closeThrows();
        FakeGateway gB = new FakeGateway("bb").tools(tool("t2"));
        FakeFactory factory = new FakeFactory(Map.of("aa", gA, "bb", gB))
                .connectError("cc", new McpConnectException("握手失败: cc", null));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                new ToolProperties(),
                List.of(spec("aa", "/bin/a"), spec("bb", "/bin/b"), spec("cc", "/bin/c")));
        manager.startup();

        assertThatCode(manager::shutdown).doesNotThrowAnyException();

        assertThat(gA.closed).isTrue();
        assertThat(gB.closed).isTrue();
    }

    // ==================== 启动有界 ====================

    /** AC-8/AC-42：连接超过启动总预算 → 该 server UNAVAILABLE（超时原因），快速 server 仍 READY。 */
    @Test
    void startup_slowConnectBeyondBudget_markedUnavailable() {
        CountDownLatch block = new CountDownLatch(1);
        FakeGateway gFast = new FakeGateway("fast").tools(tool("echo"));
        FakeGateway gSlow = new FakeGateway("slow").tools(tool("echo2"));
        FakeFactory factory = new FakeFactory(Map.of("fast", gFast, "slow", gSlow))
                .blockConnect("slow", block);
        ToolProperties props = new ToolProperties();
        props.getMcp().setRequestTimeout(Duration.ofMillis(100));
        McpServerConnectionManager manager = newManager(factory, new RecordingRedactor(),
                props, List.of(spec("fast", "/bin/fast"), spec("slow", "/bin/slow")));

        manager.startup();
        block.countDown();

        McpServerConnection fast = manager.connections().get(0);
        McpServerConnection slow = manager.connections().get(1);
        assertThat(fast.isReady()).isTrue();
        assertThat(slow.isReady()).isFalse();
        assertThat(slow.lastError()).contains("连接超时");
        assertThat(manager.toolCallbacks()).hasSize(1);
    }

    // ==================== 脚手架 ====================

    private static McpServerConnectionManager newManager(FakeFactory factory, SecretRedactor redactor,
                                                        ToolProperties props,
                                                        List<McpProperties.ServerSpec> specs) {
        props.getMcp().getServers().addAll(specs);
        // 回调工厂 T3 前为壳：spy 为每个 READY server 产出 1 个标记回调（管理器逻辑与 T3 解耦）
        McpToolCallbackFactory callbackFactory = spy(
                new McpToolCallbackFactory(null, redactor, null, props));
        doReturn(List.of(mock(ToolCallback.class))).when(callbackFactory).build(any(), any());
        return new McpServerConnectionManager(props, factory, redactor, callbackFactory);
    }

    private static McpProperties.ServerSpec spec(String name, String command) {
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName(name);
        spec.setCommand(command);
        spec.setArgs(new ArrayList<>());
        return spec;
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, name, name + " desc", null, null, null, null);
    }

    /** 记录型 redactor：验证 stderr/错误原因确实经过脱敏通道。 */
    static class RecordingRedactor extends SecretRedactor {
        final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingRedactor() {
            super(List.of());
        }

        @Override
        public String redact(String text) {
            calls.add(text);
            return "RED::" + text;
        }
    }

    /** fake 工厂：按 server 名脚本化返回网关/抛错/阻塞。 */
    static class FakeFactory implements McpClientFactory {
        final Map<String, FakeGateway> gateways;
        final Map<String, RuntimeException> connectErrors = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger connectCount = new AtomicInteger();
        volatile CountDownLatch blockLatch;
        volatile String blockName;
        volatile Duration capturedRequestTimeout;
        volatile Duration capturedInitTimeout;

        FakeFactory(Map<String, FakeGateway> gateways) {
            this.gateways = gateways;
        }

        FakeFactory connectError(String name, RuntimeException e) {
            connectErrors.put(name, e);
            return this;
        }

        FakeFactory blockConnect(String name, CountDownLatch latch) {
            this.blockName = name;
            this.blockLatch = latch;
            return this;
        }

        @Override
        public McpClientGateway connect(McpProperties.ServerSpec spec, Duration requestTimeout,
                                        Duration initTimeout) {
            connectCount.incrementAndGet();
            capturedRequestTimeout = requestTimeout;
            capturedInitTimeout = initTimeout;
            if (blockLatch != null && spec.getName().equals(blockName)) {
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted in fake connect", e);
                }
            }
            RuntimeException err = connectErrors.get(spec.getName());
            if (err != null) {
                throw err;
            }
            return gateways.get(spec.getName());
        }
    }

    /** fake 网关：脚本化工具/stderr/崩溃/close 行为，不触碰 SDK。 */
    static class FakeGateway implements McpClientGateway {
        final String name;
        List<McpSchema.Tool> tools = List.of();
        RuntimeException listToolsError;
        volatile boolean closed;
        boolean closeThrowsOnClose;
        volatile java.util.function.Consumer<String> stderrHandler;
        volatile Runnable crashHandler;

        FakeGateway(String name) {
            this.name = name;
        }

        FakeGateway tools(McpSchema.Tool... tools) {
            this.tools = List.of(tools);
            return this;
        }

        FakeGateway listToolsError(RuntimeException e) {
            this.listToolsError = e;
            return this;
        }

        FakeGateway closeThrows() {
            this.closeThrowsOnClose = true;
            return this;
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            if (listToolsError != null) {
                throw listToolsError;
            }
            return tools;
        }

        @Override
        public McpSchema.CallToolResult callTool(String rawToolName, Map<String, Object> args) {
            throw new UnsupportedOperationException("T2 不涉及 callTool");
        }

        @Override
        public void onStderr(java.util.function.Consumer<String> lineHandler) {
            this.stderrHandler = lineHandler;
        }

        @Override
        public void onCrash(Runnable crashHandler) {
            this.crashHandler = crashHandler;
        }

        @Override
        public void close() {
            closed = true;
            if (closeThrowsOnClose) {
                throw new RuntimeException("close boom: " + name);
            }
        }

        void fireCrash() {
            crashHandler.run();
        }

        void emitStderr(String line) {
            stderrHandler.accept(line);
        }
    }
}
