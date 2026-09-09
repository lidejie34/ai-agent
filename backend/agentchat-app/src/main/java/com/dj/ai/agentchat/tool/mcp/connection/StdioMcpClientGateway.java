package com.dj.ai.agentchat.tool.mcp.connection;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 mcp-core 0.14.0 stdio 的 {@link McpClientGateway} 生产实现（插入迭代4，T2）。
 *
 * <p>崩溃检测（SDK 实证）：{@code transport.getErrorSink()} 是 unicast {@code Sinks.Many<String>}
 * （stderr 行流，SDK 内部已独占订阅），不可二次订阅；改用 SDK 提供的
 * {@link StdioClientTransport#awaitForExit()}（{@code process.waitFor()}）守护线程——
 * 子进程退出即唤醒，主动 {@link #close()}（destroy 子进程）以 {@code closing} 守卫区分。
 */
@Slf4j
class StdioMcpClientGateway implements McpClientGateway {

    private final String name;
    private final McpSyncClient client;
    private final StdioClientTransport transport;

    private volatile boolean closing = false;
    private volatile boolean exited = false;
    private final List<Runnable> crashHandlers = new CopyOnWriteArrayList<>();

    StdioMcpClientGateway(String name, McpSyncClient client, StdioClientTransport transport) {
        this.name = name;
        this.client = client;
        this.transport = transport;

        Thread watcher = new Thread(() -> {
            try {
                transport.awaitForExit();
            } catch (Throwable t) {
                if (!closing) {
                    log.warn("MCP 进程退出等待异常: name={}, 原因={}", name, t.getMessage());
                }
            }
            exited = true;
            if (closing) {
                return;
            }
            log.warn("MCP server 进程退出（崩溃信号）: name={}", name);
            for (Runnable handler : crashHandlers) {
                try {
                    handler.run();
                } catch (Throwable t) {
                    log.warn("MCP 崩溃回调异常（不影响其余处理）: name={}, 原因={}", name, t.getMessage());
                }
            }
        }, "mcp-crash-watch-" + name);
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public List<McpSchema.Tool> listTools() {
        McpSchema.ListToolsResult result = client.listTools();
        return result == null || result.tools() == null ? List.of() : result.tools();
    }

    @Override
    public McpSchema.CallToolResult callTool(String rawToolName, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(rawToolName,
                args == null ? Map.of() : args));
    }

    @Override
    public void onStderr(Consumer<String> lineHandler) {
        transport.setStdErrorHandler(line -> {
            try {
                lineHandler.accept(line);
            } catch (Throwable t) {
                // stderr 消费异常绝不能外溢到 SDK error 线程（堵管道会死子进程，AC-11）
                log.warn("MCP stderr 处理器异常（已吞掉，不影响协议通道）: name={}, 原因={}",
                        name, t.getMessage());
            }
        });
    }

    @Override
    public void onCrash(Runnable crashHandler) {
        crashHandlers.add(crashHandler);
        // 注册前已退出（极窄时序窗口）：立即触发
        if (exited && !closing) {
            crashHandler.run();
        }
    }

    @Override
    public void close() {
        closing = true;
        try {
            boolean graceful = client.closeGracefully();
            if (!graceful) {
                log.warn("MCP client 未能在 SDK 超时内优雅关闭（子进程可能已残留，需运维核验）: name={}", name);
            }
        } catch (Throwable t) {
            log.warn("MCP client 关闭异常（不影响其余连接关闭）: name={}, 原因={}", name, t.getMessage());
        }
    }
}
