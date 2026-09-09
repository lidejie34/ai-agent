package com.dj.ai.agentchat.tool.mcp.admin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP server 只读视图（迭代4 T5，AC-32/35）。
 *
 * <p><b>安全红线</b>：响应中<b>不含 env</b>（AC-27/AC-32）——env 可能含密钥，
 * 仅允许存在于部署配置；本视图只暴露 command/args（运维排查所需）。
 *
 * @param name        server 逻辑名
 * @param command     启动命令（绝对路径建议）
 * @param args        启动参数数组
 * @param status      READY / UNAVAILABLE
 * @param toolCount   发现工具数（UNAVAILABLE 为 0）
 * @param tools       发现工具清单（UNAVAILABLE 为空）
 * @param lastError   最近失败原因（脱敏截断，可空）
 * @param connectedAt READY 连接建立时间（UNAVAILABLE 为 null）
 */
public record McpServerView(String name,
                            String command,
                            List<String> args,
                            String status,
                            int toolCount,
                            List<McpToolView> tools,
                            String lastError,
                            LocalDateTime connectedAt) {
}
