package com.dj.ai.agentchat.tool.mcp.connection;

/**
 * MCP 连接/握手失败（插入迭代4，T2）：命令不存在、子进程启动即退、initialize 握手超时/协议错误。
 * RuntimeException 体系——由连接管理器逐 server 失败隔离（AC-7），不阻断应用启动。
 */
public class McpConnectException extends RuntimeException {

    public McpConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
