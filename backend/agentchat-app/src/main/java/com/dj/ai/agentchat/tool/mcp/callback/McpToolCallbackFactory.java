package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * MCP 工具包装回调工厂（插入迭代4，T2 壳 / T3 实现）：把一个 READY 连接发现的
 * 每个 {@code McpSchema.Tool} 包装为 {@link McpToolCallback}（复刻 DbToolCallback 全套横切：
 * ToolContext 桥接 / 帧 / 审计 handlerType=MCP / 脱敏截断 / 超时 / 幂等 / 全捕获，无 guide）。
 */
@Slf4j
public class McpToolCallbackFactory {

    private final ToolAuditService auditService;
    private final SecretRedactor redactor;
    private final ExecutorService toolExecutor;
    private final ToolProperties properties;

    public McpToolCallbackFactory(ToolAuditService auditService, SecretRedactor redactor,
                                  ExecutorService toolExecutor, ToolProperties properties) {
        this.auditService = auditService;
        this.redactor = redactor;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 为 READY 连接的全部发现工具构建包装回调。
     *
     * @param connection      READY 连接（持网关/工具快照）
     * @param markUnavailable 崩溃标记出口（reason 已脱敏 → 管理器 markUnavailable，AC-10）
     */
    public List<ToolCallback> build(McpServerConnection connection, Consumer<String> markUnavailable) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> seenExposed = new HashSet<>();
        for (McpSchema.Tool raw : connection.tools()) {
            if (raw == null || raw.name() == null || raw.name().isBlank()) {
                log.warn("MCP 发现工具缺少 name，跳过: server={}", connection.name());
                continue;
            }
            String exposed = McpToolNames.expose(connection.name(), raw.name());
            if (McpToolNames.normalized(connection.name(), raw.name()).length()
                    > McpToolNames.MAX_NAME_LENGTH) {
                log.warn("MCP 工具暴露名超长已截断+hash: server={}, raw={}, exposed={}",
                        connection.name(), raw.name(), exposed);
            }
            if (!seenExposed.add(exposed)) {
                // 归一化碰撞（如同 server 内 Foo/foo）：跳过 + WARN，不阻断其余（D9 语义）
                log.warn("MCP 工具暴露名归一化后重复，跳过: server={}, raw={}, exposed={}",
                        connection.name(), raw.name(), exposed);
                continue;
            }
            callbacks.add(new McpToolCallback(raw, exposed, connection.gateway(),
                    connection::isReady, markUnavailable,
                    auditService, redactor, toolExecutor, properties));
        }
        return List.copyOf(callbacks);
    }
}
