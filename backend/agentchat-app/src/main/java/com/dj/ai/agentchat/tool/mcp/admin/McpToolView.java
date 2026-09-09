package com.dj.ai.agentchat.tool.mcp.admin;

/**
 * MCP 发现工具的只读视图（迭代4 T5，AC-32）：
 *
 * @param name        模型可见的暴露名（server 命名空间前缀归一化形式）
 * @param rawName     server 声明的原始工具名
 * @param description 工具描述（逐字来自 server 发现结果，可空）
 */
public record McpToolView(String name, String rawName, String description) {
}
