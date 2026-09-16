package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ToolsAvailableView;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolNames;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话级工具选择器选项端点（迭代12 D6，公开只读）：聊天页工具/MCP 选择器的数据源。
 *
 * <p>常驻组件扫描；两侧依赖均经 {@link ObjectProvider} 可选解析——工具总开关关闭
 * （ToolRegistry 缺席）或 MCP 子开关关闭（ConnectionManager 缺席）→ 对应侧空列表，
 * 前端静默隐藏选择器（同 {@link KbDimensionController} RAG 关闭纪律）。
 * 任何供给侧异常只 warn 并降级为空列表，绝不 5xx（选择器是增强，不是硬依赖）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
public class ToolsAvailableController {

    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ObjectProvider<McpServerConnectionManager> mcpConnectionManagerProvider;

    public ToolsAvailableController(ObjectProvider<ToolRegistry> toolRegistryProvider,
                                    ObjectProvider<McpServerConnectionManager> mcpConnectionManagerProvider) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.mcpConnectionManagerProvider = mcpConnectionManagerProvider;
    }

    @GetMapping("/available")
    public ToolsAvailableView available() {
        return new ToolsAvailableView(dbTools(), mcpServers());
    }

    private List<ToolsAvailableView.DbToolItem> dbTools() {
        ToolRegistry registry = toolRegistryProvider == null ? null : toolRegistryProvider.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        try {
            List<ToolCallback> callbacks = registry.toolCallbacks();
            if (callbacks == null) {
                return List.of();
            }
            return callbacks.stream()
                    .map(ToolsAvailableController::toDbItem)
                    .filter(item -> item != null)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("DB 工具清单获取失败，按空列表降级: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ToolsAvailableView.McpServerItem> mcpServers() {
        McpServerConnectionManager manager = mcpConnectionManagerProvider == null
                ? null : mcpConnectionManagerProvider.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        try {
            return manager.connections().stream()
                    .map(ToolsAvailableController::toServerItem)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("MCP server 清单获取失败，按空列表降级: {}", e.getMessage());
            return List.of();
        }
    }

    private static ToolsAvailableView.DbToolItem toDbItem(ToolCallback cb) {
        try {
            if (cb.getToolDefinition() == null || cb.getToolDefinition().name() == null) {
                return null;
            }
            String description = cb.getToolDefinition().description();
            return new ToolsAvailableView.DbToolItem(cb.getToolDefinition().name(),
                    description == null ? "" : description);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ToolsAvailableView.McpServerItem toServerItem(McpServerConnection conn) {
        List<String> exposed = conn.tools() == null ? List.of()
                : conn.tools().stream()
                        .map(t -> McpToolNames.expose(conn.name(), t.name()))
                        .toList();
        return new ToolsAvailableView.McpServerItem(conn.name(), conn.status().name(), exposed);
    }
}
