package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;

import java.time.Duration;

/**
 * MCP 连接工厂（插入迭代4，T2）：装配 stdio 子进程 + SDK 握手，返回已 initialize 的网关。
 *
 * <p>生产实现 {@link StdioMcpClientFactory} 是全工程唯一接触 SDK 装配
 * （{@code StdioClientTransport}/{@code McpClient.sync(...)}）的位置；单测以 fake 替换。
 * 命令仅来自部署配置 {@link McpProperties.ServerSpec}（AC-5/AC-27：HTTP/模型入参不可达此路径）。
 */
public interface McpClientFactory {

    /**
     * 装配并握手一个 stdio 连接；命令不存在/启动即退/握手失败抛
     * {@link McpConnectException}（RuntimeException），由连接管理器失败隔离。
     *
     * @param spec           server 启动声明（command 绝对路径 + args 数组 + env 叠加）
     * @param requestTimeout SDK 协议层请求超时（= 包装层执行超时 - 2s 缓冲，R3）
     * @param initTimeout    SDK 握手超时（= app.tools.mcp.request-timeout，AC-8）
     */
    McpClientGateway connect(McpProperties.ServerSpec spec, Duration requestTimeout, Duration initTimeout);
}
