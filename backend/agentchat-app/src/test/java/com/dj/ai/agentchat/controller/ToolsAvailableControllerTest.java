package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ToolsAvailableView;
import com.dj.ai.agentchat.tool.mcp.McpProperties;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolNames;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerStatus;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/tools/available（迭代12 FR-4）纯单测：
 * DB 工具名+描述、MCP server 名/状态/暴露工具名（{@code <server>_<tool>} 归一形态）；
 * 任一侧 bean 缺席或供给侧异常 → 该侧空列表，绝不 5xx。
 */
class ToolsAvailableControllerTest {

    /** 最小 ObjectProvider 桩（controller 包不可见 ChatService.FixedObjectProvider）。 */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    private ToolCallback namedCallback(String name, String description) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name).description(description).inputSchema("{}").build());
        return cb;
    }

    private McpServerConnection conn(String name, McpServerStatus status, String... toolNames) {
        List<McpSchema.Tool> tools = Arrays.stream(toolNames)
                .map(n -> new McpSchema.Tool(n, n, n + " desc", null, null, null, null))
                .toList();
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName(name);
        spec.setCommand("/bin/x");
        spec.setArgs(new ArrayList<>());
        return new McpServerConnection(name, spec, null, tools, status, null);
    }

    @Test
    void available_listsDbToolsAndMcpServers() {
        // 注意：namedCallback 内部含 when() 打桩，必须先落成局部变量再进 thenReturn，
        // 否则外层 when(...).thenReturn(namedCallback(...)) 触发 UnfinishedStubbing
        ToolCallback analyzeLog = namedCallback("analyze_log", "分析日志");
        ToolCallback errorCount = namedCallback("log_error_count", "统计错误");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.toolCallbacks()).thenReturn(List.of(analyzeLog, errorCount));
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.connections()).thenReturn(List.of(
                conn("easy-mysql", McpServerStatus.READY, "query"),
                conn("dead", McpServerStatus.UNAVAILABLE)));

        ToolsAvailableView view = new ToolsAvailableController(
                providerOf(registry), providerOf(manager)).available();

        assertThat(view.dbTools()).hasSize(2);
        assertThat(view.dbTools().get(0).name()).isEqualTo("analyze_log");
        assertThat(view.dbTools().get(0).description()).isEqualTo("分析日志");
        assertThat(view.mcpServers()).hasSize(2);
        ToolsAvailableView.McpServerItem ready = view.mcpServers().get(0);
        assertThat(ready.name()).isEqualTo("easy-mysql");
        assertThat(ready.status()).isEqualTo("READY");
        // 暴露名 = 挂载形态（<server>_<tool> 归一），与选择后过滤的 server key 同源
        assertThat(ready.tools()).containsExactly(McpToolNames.expose("easy-mysql", "query"));
        assertThat(view.mcpServers().get(1).status()).isEqualTo("UNAVAILABLE");
        assertThat(view.mcpServers().get(1).tools()).isEmpty();
    }

    @Test
    void providersAbsent_emptyListsBothSides() {
        ToolsAvailableView view = new ToolsAvailableController(
                ToolsAvailableControllerTest.<ToolRegistry>providerOf(null),
                ToolsAvailableControllerTest.<McpServerConnectionManager>providerOf(null)).available();

        assertThat(view.dbTools()).isEmpty();
        assertThat(view.mcpServers()).isEmpty();
    }

    @Test
    void registryThrows_degradesToEmptyDbList_mcpIntact() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.toolCallbacks()).thenThrow(new RuntimeException("注册中心炸了"));
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.connections()).thenReturn(List.of(conn("easy-mysql", McpServerStatus.READY, "query")));

        ToolsAvailableView view = new ToolsAvailableController(
                providerOf(registry), providerOf(manager)).available();

        assertThat(view.dbTools()).isEmpty();
        assertThat(view.mcpServers()).hasSize(1);
    }

    @Test
    void mcpManagerThrows_degradesToEmptyServerList_dbIntact() {
        ToolCallback analyzeLog = namedCallback("analyze_log", "");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.toolCallbacks()).thenReturn(List.of(analyzeLog));
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.connections()).thenThrow(new RuntimeException("manager 炸了"));

        ToolsAvailableView view = new ToolsAvailableController(
                providerOf(registry), providerOf(manager)).available();

        assertThat(view.dbTools()).hasSize(1);
        assertThat(view.mcpServers()).isEmpty();
    }
}
