package com.dj.ai.agentchat.tool.mcp.admin;

import com.dj.ai.agentchat.tool.mcp.callback.McpToolNames;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerStatus;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 管理端只读服务（迭代4 T5，FR-6）：从连接管理器快照装配 server 视图。
 * 随 MCP 子开关条件装配；控制器在 bean 缺席时返回 {@code {"servers":[]}}（AC-35）。
 */
@Slf4j
public class AdminMcpService {

    /** lastError 视图截断长度（管理器侧已脱敏）。 */
    private static final int LAST_ERROR_MAX_CHARS = 500;

    private final McpServerConnectionManager connectionManager;

    public AdminMcpService(McpServerConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /** 当前全部配置 server 的只读视图（配置顺序；含 UNAVAILABLE；不含 env，AC-32）。 */
    public List<McpServerView> listServers() {
        List<McpServerView> views = new ArrayList<>();
        for (McpServerConnection conn : connectionManager.connections()) {
            List<McpToolView> tools = new ArrayList<>();
            for (McpSchema.Tool raw : conn.tools()) {
                if (raw == null || raw.name() == null) {
                    continue;
                }
                tools.add(new McpToolView(
                        McpToolNames.expose(conn.name(), raw.name()),
                        raw.name(),
                        raw.description()));
            }
            String command = conn.spec() != null ? conn.spec().getCommand() : null;
            List<String> args = conn.spec() != null && conn.spec().getArgs() != null
                    ? List.copyOf(conn.spec().getArgs()) : List.of();
            // 绝不含 env（AC-27/AC-32）
            views.add(new McpServerView(
                    conn.name(),
                    command,
                    args,
                    conn.status() == McpServerStatus.READY ? "READY" : "UNAVAILABLE",
                    tools.size(),
                    List.copyOf(tools),
                    truncate(conn.lastError()),
                    conn.connectedAt()));
        }
        return views;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= LAST_ERROR_MAX_CHARS ? text
                : text.substring(0, LAST_ERROR_MAX_CHARS) + "...[已截断]";
    }
}
