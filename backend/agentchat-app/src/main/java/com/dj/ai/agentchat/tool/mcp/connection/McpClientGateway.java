package com.dj.ai.agentchat.tool.mcp.connection;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 单个 MCP server 连接的运行时网关（插入迭代4，T2）：抽象 SDK 调用面，
 * 生产实现 {@link StdioMcpClientGateway}（mcp-core 0.14.0 stdio），单测以 fake 替换离线验证。
 *
 * <p>SDK 0.14.0 实证修正（对技术方案的偏离，已核实 sources jar）：
 * {@code StdioClientTransport.getErrorSink()} 返回 {@code Sinks.Many<String>}，
 * 承载的是 <b>stderr 文本行</b>（且为 unicast 单订阅，SDK 内部已订阅转发给
 * stdErrorHandler），进程退出时 stderr 读循环 EOF → sink complete。故本接口不暴露
 * {@code Flux<McpError>}：stderr 行经 {@link #onStderr(Consumer)} 消费（AC-11），
 * 进程崩溃信号经 {@link #onCrash(Runnable)}（基于 {@code transport.awaitForExit()}
 * 的守护线程，主动 {@link #close()} 不触发）。
 */
public interface McpClientGateway extends AutoCloseable {

    /**
     * 握手后发现工具（tools/list 一次性快照；本迭代不订阅 list_changed，D13/AC-17）。
     */
    List<McpSchema.Tool> listTools();

    /**
     * 调用工具（tools/call）。server 业务失败返回 {@code CallToolResult.isError()==true}
     * （0.14.0 不抛异常）；连接断裂/进程崩溃/协议错误/SDK 超时抛
     * {@link RuntimeException}（典型为 {@code McpError} 或 reactor TimeoutException 包装）。
     */
    McpSchema.CallToolResult callTool(String rawToolName, Map<String, Object> args);

    /**
     * 注册 stderr 行处理器（SDK error 线程回调；实现保证 handler 异常不外溢堵管道，AC-11）。
     */
    void onStderr(Consumer<String> lineHandler);

    /**
     * 注册进程崩溃/传输断裂回调（进程退出 EOF 触发）；主动 {@link #close()} 不触发。
     * 回调内做 markUnavailable（仅标记不重启，D11/AC-10）。
     */
    void onCrash(Runnable crashHandler);

    /**
     * 优雅关闭：closeGracefully（destroy 子进程 + 等待退出 + scheduler dispose，AC-9）。
     * 实现全捕获，单连接关闭异常不影响其余连接。
     */
    @Override
    void close();
}
