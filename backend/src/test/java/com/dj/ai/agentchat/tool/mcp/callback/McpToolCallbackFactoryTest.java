package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mcp.McpProperties;
import com.dj.ai.agentchat.tool.mcp.connection.McpClientGateway;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerStatus;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * McpToolCallbackFactory 单测（迭代4 T3，AC-12/14）：READY 连接的发现工具逐个包装、
 * 暴露名前缀归一化、归一化碰撞/坏行跳过。
 */
class McpToolCallbackFactoryTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "mcp-factory-test");
        t.setDaemon(true);
        return t;
    });

    private McpToolCallbackFactory factory() {
        ToolAuditService auditService = new ToolAuditService(mock(AgentToolCallLogMapper.class));
        return new McpToolCallbackFactory(auditService, new SecretRedactor(List.of()),
                executor, new ToolProperties());
    }

    private McpServerConnection connection(String serverName, List<McpSchema.Tool> tools) {
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName(serverName);
        spec.setCommand("/bin/x");
        McpClientGateway gateway = mock(McpClientGateway.class);
        return new McpServerConnection(serverName, spec, gateway, tools, McpServerStatus.READY, null);
    }

    private McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, name, name + " desc", null, null, null, null);
    }

    @Test
    void build_wrapsEveryToolWithPrefixedName() {
        McpServerConnection conn = connection("my-fs",
                List.of(tool("Read-File"), tool("write_file")));

        List<ToolCallback> callbacks = factory().build(conn, reason -> {
        });

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("my_fs_read_file");
        assertThat(callbacks.get(1).getToolDefinition().name()).isEqualTo("my_fs_write_file");
        assertThat(callbacks.get(0).getToolDefinition().description()).isEqualTo("Read-File desc");
    }

    /** AC-14 同 server 内归一化碰撞（Foo/foo）：跳过 + WARN，不重复挂载。 */
    @Test
    void build_normalizedNameCollision_skipsDuplicate() {
        McpServerConnection conn = connection("fs", new ArrayList<>(List.of(
                tool("Foo"), tool("foo"), tool("bar"))));

        List<ToolCallback> callbacks = factory().build(conn, reason -> {
        });

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("fs_foo", "fs_bar");
    }

    @Test
    void build_nullOrBlankToolName_skipped() {
        McpServerConnection conn = connection("fs", new ArrayList<>(List.of(
                tool("ok"), new McpSchema.Tool(null, null, null, null, null, null, null),
                new McpSchema.Tool("  ", null, null, null, null, null, null))));

        List<ToolCallback> callbacks = factory().build(conn, reason -> {
        });

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("fs_ok");
    }

    @Test
    void build_emptyTools_returnsEmpty() {
        McpServerConnection conn = connection("fs", List.of());
        assertThat(factory().build(conn, reason -> {
        })).isEmpty();
    }
}
