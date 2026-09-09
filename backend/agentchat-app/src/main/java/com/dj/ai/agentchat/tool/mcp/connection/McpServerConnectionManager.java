package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.mcp.McpProperties;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolCallbackFactory;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP server 连接管理器（插入迭代4，T2，FR-2）：启动期 eager 并行连接配置的 stdio server、
 * initialize 握手、listTools 一次性发现（不订阅 list_changed，D13/AC-17），失败逐 server
 * 隔离为 UNAVAILABLE + WARN（不阻断启动，AC-7）；运行期崩溃经 errorSink/退出信号
 * {@link #markUnavailable(String, String)} 摘除回调（不自动重启，D11/AC-10）；
 * {@code @PreDestroy} 对全部连接（含启动失败半启动网关）closeGracefully（AC-9）。
 *
 * <p>启动有界（AC-8/AC-42）：并行连接 + SDK initializationTimeout（=mcp.request-timeout）
 * 兜底，总预算 = 握手超时 + 10s 调度余量；预算到期未完成的连接按 UNAVAILABLE 处理。
 */
@Slf4j
public class McpServerConnectionManager implements ApplicationRunner {

    /** 执行超时硬顶（与 DbToolCallback 一致）。 */
    static final int EXEC_TIMEOUT_HARD_CAP_MS = 60000;
    /** SDK 协议请求超时相对执行超时的缓冲（R3：SDK 先超时，包装层识别为 TIMEOUT）。 */
    static final long SDK_TIMEOUT_BUFFER_MS = 2000;
    static final long SDK_TIMEOUT_FLOOR_MS = 5000;
    /** 启动总预算余量（并行调度 + 发现）。 */
    private static final long STARTUP_BUDGET_EXTRA_MS = 10_000;

    private final ToolProperties toolProperties;
    private final McpClientFactory clientFactory;
    private final SecretRedactor redactor;
    private final McpToolCallbackFactory callbackFactory;

    private volatile Map<String, McpServerConnection> connections = Collections.emptyMap();
    private volatile List<ToolCallback> callbackSnapshot = List.of();

    public McpServerConnectionManager(ToolProperties toolProperties,
                                      McpClientFactory clientFactory,
                                      SecretRedactor redactor,
                                      McpToolCallbackFactory callbackFactory) {
        this.toolProperties = toolProperties;
        this.clientFactory = clientFactory;
        this.redactor = redactor;
        this.callbackFactory = callbackFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        startup();
    }

    /**
     * 启动连接流程：配置校验 → filesystem 目录准备 → 并行握手发现 → 失败隔离 → 快照重建。
     */
    public void startup() {
        List<McpProperties.ServerSpec> specs = mcpProps().getServers();
        if (specs == null || specs.isEmpty()) {
            log.info("MCP: 未配置 server（app.tools.mcp.servers 为空），不启动 stdio 子进程");
            rebuild(new LinkedHashMap<>());
            return;
        }

        Duration initTimeout = mcpProps().getRequestTimeout() != null
                ? mcpProps().getRequestTimeout() : Duration.ofSeconds(20);
        long budgetMs = initTimeout.toMillis() + STARTUP_BUDGET_EXTRA_MS;

        ExecutorService pool = Executors.newCachedThreadPool(new DaemonThreadFactory("mcp-startup"));
        try {
            List<CompletableFuture<McpServerConnection>> futures = new ArrayList<>();
            for (McpProperties.ServerSpec spec : specs) {
                futures.add(CompletableFuture.supplyAsync(() -> connectOne(spec, initTimeout), pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(budgetMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("MCP 启动总预算 {}ms 到期，未完成连接按 UNAVAILABLE 处理（AC-8/AC-42）", budgetMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("MCP 启动等待被中断，未完成连接按 UNAVAILABLE 处理");
            } catch (Throwable t) {
                log.warn("MCP 启动等待异常（逐 server 结果继续收敛）: {}", t.getMessage());
            }

            Map<String, McpServerConnection> map = new LinkedHashMap<>();
            for (int i = 0; i < specs.size(); i++) {
                McpServerConnection conn;
                CompletableFuture<McpServerConnection> future = futures.get(i);
                if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                    try {
                        conn = future.get();
                    } catch (Exception e) {
                        conn = unavailable(specs.get(i), "连接任务异常: " + safeMessage(e));
                    }
                } else {
                    future.cancel(true);
                    conn = unavailable(specs.get(i), "连接超时（启动预算 " + budgetMs + "ms 到期，AC-8）");
                }
                putWithDupCheck(map, conn);
            }
            rebuild(map);
        } finally {
            pool.shutdownNow();
        }
        log.info("MCP 启动完成: server 总数={}, READY={}, UNAVAILABLE={}, 挂载工具数={}",
                connections.size(), countReady(), connections.size() - countReady(),
                callbackSnapshot.size());
    }

    /**
     * 当前可挂载的 MCP 工具回调快照（READY server；UNAVAILABLE 已摘除）。供 McpToolProvider。
     */
    public List<ToolCallback> toolCallbacks() {
        return callbackSnapshot;
    }

    /**
     * 当前连接视图（配置顺序；含 UNAVAILABLE）。供管理端只读视图。
     */
    public List<McpServerConnection> connections() {
        return List.copyOf(connections.values());
    }

    /**
     * 运行期崩溃降级（AC-10/D11）：状态翻 UNAVAILABLE、记录脱敏原因、从回调快照摘除该 server；
     * <b>不自动重启</b>；其他 server 与 DB 工具不受影响。重复调用幂等。
     */
    public void markUnavailable(String name, String reason) {
        McpServerConnection conn = connections.get(name);
        if (conn == null || !conn.isReady()) {
            return;
        }
        String safeReason = redactor.redact(safeMessage(reason));
        conn.markUnavailable(safeReason);
        log.warn("MCP server 运行期崩溃/断裂，标记 UNAVAILABLE 并摘除工具（不自动重启，D11）: name={}, 原因={}",
                name, safeReason);
        List<ToolCallback> remaining = new ArrayList<>();
        for (McpServerConnection c : connections.values()) {
            if (c.isReady()) {
                remaining.addAll(c.callbacks());
            }
        }
        this.callbackSnapshot = List.copyOf(remaining);
    }

    /** 应用关闭：回收全部子进程（含半启动），单连接异常不影响其余（AC-9）。 */
    @PreDestroy
    public void shutdown() {
        log.info("MCP 连接管理器关闭：回收全部 server 子进程（AC-9）");
        for (McpServerConnection conn : connections.values()) {
            conn.close();
        }
    }

    // ==================== 内部 ====================

    private McpServerConnection connectOne(McpProperties.ServerSpec spec, Duration initTimeout) {
        String name = spec.getName() == null ? "<unnamed>" : spec.getName();
        Optional<String> invalid = ServerSpecValidator.invalidReason(spec);
        if (invalid.isPresent()) {
            log.warn("MCP server 配置非法，跳过连接: name={}, 原因={}", name, invalid.get());
            return unavailable(spec, "配置非法: " + invalid.get());
        }
        ServerSpecValidator.prepareFilesystemDirs(spec,
                w -> log.warn("MCP filesystem 工作目录告警: {}", redactor.redact(w)));

        McpClientGateway gateway = null;
        try {
            gateway = clientFactory.connect(spec, sdkRequestTimeout(), initTimeout);
            List<McpSchema.Tool> tools = gateway.listTools();
            gateway.onStderr(line -> log.info("[mcp:{}] stderr: {}", name, redactor.redact(line)));
            gateway.onCrash(() -> markUnavailable(name, "MCP server 进程退出/传输断裂"));
            log.info("MCP server READY: name={}, 发现工具数={}", name, tools.size());
            return new McpServerConnection(name, spec, gateway, tools, McpServerStatus.READY, null);
        } catch (Throwable t) {
            // 半启动回收：握手成功但 listTools/订阅前失败，网关可能已持子进程（R7）
            if (gateway != null) {
                try {
                    gateway.close();
                } catch (Throwable ignored) {
                    // 尽力回收
                }
            }
            String reason = redactor.redact(safeMessage(t));
            log.warn("MCP server 连接失败，标记 UNAVAILABLE（失败隔离，不阻断启动/其他 server）: name={}, 原因={}",
                    name, reason);
            return unavailable(spec, reason);
        }
    }

    /** 重建连接 map + 回调快照（READY server 经工厂包装；UNAVAILABLE 无回调）。 */
    private void rebuild(Map<String, McpServerConnection> map) {
        List<ToolCallback> all = new ArrayList<>();
        for (McpServerConnection conn : map.values()) {
            if (conn.isReady()) {
                List<ToolCallback> built = callbackFactory.build(
                        conn, reason -> markUnavailable(conn.name(), reason));
                conn.setCallbacks(built);
                all.addAll(built);
            }
        }
        this.connections = Collections.unmodifiableMap(new LinkedHashMap<>(map));
        this.callbackSnapshot = List.copyOf(all);
    }

    private static McpServerConnection unavailable(McpProperties.ServerSpec spec, String reason) {
        String name = spec != null && spec.getName() != null ? spec.getName() : "<unnamed>";
        return new McpServerConnection(name, spec, null, List.of(),
                McpServerStatus.UNAVAILABLE, reason);
    }

    private static void putWithDupCheck(Map<String, McpServerConnection> map, McpServerConnection conn) {
        if (map.containsKey(conn.name())) {
            log.warn("MCP server 配置重名，后者覆盖: name={}", conn.name());
        }
        map.put(conn.name(), conn);
    }

    private long countReady() {
        return connections.values().stream().filter(McpServerConnection::isReady).count();
    }

    private McpProperties mcpProps() {
        return toolProperties.getMcp();
    }

    /** SDK 协议请求超时 = min(执行超时默认值, 硬顶 60s) - 2s 缓冲（R3），下限 5s。 */
    private Duration sdkRequestTimeout() {
        long exec = Math.min(Math.max(1000, toolProperties.getDefaultTimeoutMs()),
                EXEC_TIMEOUT_HARD_CAP_MS);
        return Duration.ofMillis(Math.max(SDK_TIMEOUT_FLOOR_MS, exec - SDK_TIMEOUT_BUFFER_MS));
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }

    private static String safeMessage(String message) {
        return message != null && !message.isBlank() ? message : "未知错误";
    }

    /** daemon 线程工厂（启动并行连接池）。 */
    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
