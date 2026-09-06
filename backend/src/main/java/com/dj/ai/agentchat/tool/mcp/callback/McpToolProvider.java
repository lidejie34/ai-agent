package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

/**
 * MCP 工具回调供给（插入迭代4，T4，FR-3）：实现 Spring AI
 * {@link ToolCallbackProvider} 仅为类型统一——<b>不由 spring-ai-starter-mcp-client
 * 自动注册/发现</b>（路径 B 手工装配，AC-16）；回调全部来自
 * {@link McpServerConnectionManager#toolCallbacks()} 的 volatile 快照
 * （READY server；崩溃摘除后即时收敛，不重启 D11）。
 *
 * <p>合并点在 {@code DefaultToolSupport}：DB 回调在前、MCP 回调在后，
 * 同名冲突跳过 MCP（D9）。
 */
@Slf4j
public class McpToolProvider implements ToolCallbackProvider {

    private final McpServerConnectionManager connectionManager;

    public McpToolProvider(McpServerConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> snapshot = connectionManager.toolCallbacks();
        return snapshot.toArray(new ToolCallback[0]);
    }
}
