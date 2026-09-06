package com.dj.ai.agentchat.tool.mcp.callback;

import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * McpToolProvider 快照供给单测（迭代4 T4）。
 */
class McpToolProviderTest {

    @Test
    void getToolCallbacks_returnsManagerSnapshotAsArray() {
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        ToolCallback cb1 = mock(ToolCallback.class);
        ToolCallback cb2 = mock(ToolCallback.class);
        when(manager.toolCallbacks()).thenReturn(List.of(cb1, cb2));

        ToolCallback[] callbacks = new McpToolProvider(manager).getToolCallbacks();

        assertThat(callbacks).containsExactly(cb1, cb2);
    }

    @Test
    void getToolCallbacks_emptySnapshot_returnsEmptyArray() {
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.toolCallbacks()).thenReturn(List.of());

        assertThat(new McpToolProvider(manager).getToolCallbacks()).isEmpty();
    }
}
