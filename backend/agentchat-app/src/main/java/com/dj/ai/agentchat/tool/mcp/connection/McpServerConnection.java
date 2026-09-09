package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单个 MCP server 的运行时句柄（插入迭代4，T2）：配置快照 + 网关 + 发现工具 + 状态。
 *
 * <p>启动失败的 server 也以 {@link McpServerStatus#UNAVAILABLE} 状态留存（gateway=null、
 * tools 空），供管理端视图展示；READY 句柄持发现工具快照与包装回调（T3 装配）。
 * 状态翻转仅经 {@link #markUnavailable(String)}（崩溃降级，D11 不重启）。
 */
@Slf4j
public class McpServerConnection {

    private final String name;
    private final McpProperties.ServerSpec spec;
    /** UNAVAILABLE（启动失败）时为 null。 */
    private final McpClientGateway gateway;
    private final List<McpSchema.Tool> tools;
    private final LocalDateTime connectedAt;

    private volatile McpServerStatus status;
    private volatile String lastError;
    /** T3 装配的包装回调；UNAVAILABLE 后由管理器重建快照摘除。 */
    private volatile List<ToolCallback> callbacks = List.of();

    public McpServerConnection(String name, McpProperties.ServerSpec spec, McpClientGateway gateway,
                               List<McpSchema.Tool> tools, McpServerStatus status, String lastError) {
        this.name = name;
        this.spec = spec;
        this.gateway = gateway;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.status = status;
        this.lastError = lastError;
        this.connectedAt = status == McpServerStatus.READY ? LocalDateTime.now() : null;
    }

    public String name() {
        return name;
    }

    public McpProperties.ServerSpec spec() {
        return spec;
    }

    /** 网关；UNAVAILABLE（启动失败）返回 null。 */
    public McpClientGateway gateway() {
        return gateway;
    }

    public List<McpSchema.Tool> tools() {
        return tools;
    }

    public McpServerStatus status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }

    public LocalDateTime connectedAt() {
        return connectedAt;
    }

    public boolean isReady() {
        return status == McpServerStatus.READY;
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public void setCallbacks(List<ToolCallback> callbacks) {
        this.callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }

    /** 崩溃/断裂降级：状态翻 UNAVAILABLE、记录原因（已脱敏）；不重启（D11）。 */
    public void markUnavailable(String reason) {
        this.status = McpServerStatus.UNAVAILABLE;
        this.lastError = reason;
        this.callbacks = List.of();
    }

    /** 关闭底层网关（@PreDestroy 全连接回收，AC-9）；单连接异常不影响其余。 */
    public void close() {
        if (gateway == null) {
            return;
        }
        try {
            gateway.close();
        } catch (Throwable t) {
            log.warn("MCP server 连接关闭异常（不影响其余连接）: name={}, 原因={}", name, t.getMessage());
        }
    }
}
