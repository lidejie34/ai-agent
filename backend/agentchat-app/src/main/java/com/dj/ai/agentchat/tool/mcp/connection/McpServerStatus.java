package com.dj.ai.agentchat.tool.mcp.connection;

/**
 * MCP server 连接状态（插入迭代4，T2）。
 */
public enum McpServerStatus {

    /** 握手 + 发现成功，工具已挂载。 */
    READY,

    /** 启动失败/运行期崩溃：工具不挂载（或已摘除），不自动重启（D11），不影响其他 server 与 DB 工具。 */
    UNAVAILABLE
}
