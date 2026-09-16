package com.dj.ai.agentchat.dto;

import java.util.List;

/**
 * 对话级工具选择器选项视图（迭代12 D6）：GET /api/tools/available 响应。
 * 工具总开关关闭/依赖缺席 → 双空列表（前端静默隐藏选择器，同 /api/kb/dimensions 纪律）。
 *
 * @param dbTools    DB 注册中心启用工具（name/description 供展示）
 * @param mcpServers MCP server 组（name/状态/暴露工具名清单；仅 READY 可选）
 */
public record ToolsAvailableView(List<DbToolItem> dbTools, List<McpServerItem> mcpServers) {

    public record DbToolItem(String name, String description) {
    }

    public record McpServerItem(String name, String status, List<String> tools) {
    }
}
