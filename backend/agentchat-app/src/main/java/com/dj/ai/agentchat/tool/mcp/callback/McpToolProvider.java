package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Set;

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

    /**
     * 对话级 server 组过滤变体（迭代12 D4）：只取选中 server 的 READY 回调——
     * 源头按 server key 精确过滤（不反推暴露名前缀，规避 64 字符截断歧义）。
     */
    public ToolCallback[] getToolCallbacks(Set<String> serverNames) {
        List<ToolCallback> snapshot = connectionManager.toolCallbacks(serverNames);
        return snapshot.toArray(new ToolCallback[0]);
    }
}
