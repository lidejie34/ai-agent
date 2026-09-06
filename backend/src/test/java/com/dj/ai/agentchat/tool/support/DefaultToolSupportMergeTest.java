package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultToolSupport 的 DB+MCP 合并挂载单测（迭代4 T4，AC-3/14/16/38）：
 * DB 在前 MCP 在后、同名冲突跳过 MCP、全空返回 null、MCP 缺席与迭代 G 等价、
 * 共享 ToolContext/bridge。
 */
class DefaultToolSupportMergeTest {

    private ToolCallback namedCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
        return cb;
    }

    private ToolRegistry registryReturning(List<ToolCallback> callbacks) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.toolCallbacks()).thenReturn(callbacks);
        return registry;
    }

    private McpToolProvider providerReturning(ToolCallback... callbacks) {
        McpToolProvider provider = mock(McpToolProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    @Test
    void dbAndMcp_mergedDbFirst_shareOneBridgeAndRequestId() {
        ToolCallback db = namedCallback("db_one");
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(db)), providerReturning(mcp));

        ToolMount mount = support.mountTools("sess-1");

        assertThat(mount).isNotNull();
        assertThat(mount.callbacks()).containsExactly(db, mcp);
        assertThat(mount.toolContext().get("sessionId")).isEqualTo("sess-1");
        assertThat(mount.toolContext().get("requestId")).isInstanceOf(String.class);
        assertThat(mount.toolContext().get("toolBridge")).isSameAs(mount.bridge());
        // 无 null 值（M7 ToolContext Assert.noNullElements）
        assertThat(mount.toolContext().values()).doesNotContainNull();
    }

    /** AC-14/D9：MCP 工具与 DB 工具同名 → 跳过 MCP（DB 优先），不重复挂载。 */
    @Test
    void nameCollision_mcpSkippedDbWins() {
        ToolCallback db = namedCallback("fs_echo");
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(db)), providerReturning(mcp));

        ToolMount mount = support.mountTools(null);

        assertThat(mount.callbacks()).hasSize(1);
        assertThat(mount.callbacks().get(0)).isSameAs(db);
        // 无状态会话不放 sessionId 键（值非 null 约束）
        assertThat(mount.toolContext()).doesNotContainKey("sessionId");
    }

    /** DB 零启用但 MCP READY：仅挂载 MCP 工具。 */
    @Test
    void dbEmpty_mcpPresent_mountsMcpOnly() {
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of()), providerReturning(mcp));

        ToolMount mount = support.mountTools(null);

        assertThat(mount).isNotNull();
        assertThat(mount.callbacks()).containsExactly(mcp);
    }

    /** DB 故障降级（null）+ MCP 有工具：仍挂载 MCP。 */
    @Test
    void dbNull_mcpPresent_mountsMcpOnly() {
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(null), providerReturning(mcp));

        ToolMount mount = support.mountTools(null);

        assertThat(mount).isNotNull();
        assertThat(mount.callbacks()).containsExactly(mcp);
    }

    /** DB 与 MCP 全空（全部 UNAVAILABLE/零配置）→ null（调用方跳过 .tools()）。 */
    @Test
    void bothEmpty_returnsNull() {
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of()), providerReturning());

        assertThat(support.mountTools(null)).isNull();
    }

    /** AC-3：MCP provider 缺席（子开关关闭）→ 纯 DB 行为，与迭代 G 等价。 */
    @Test
    void mcpAbsent_dbOnlyBehavior_andNullWhenDbEmpty() {
        ToolCallback db = namedCallback("db_one");
        DefaultToolSupport withDb = new DefaultToolSupport(registryReturning(List.of(db)));
        assertThat(withDb.mountTools(null).callbacks()).containsExactly(db);

        DefaultToolSupport empty = new DefaultToolSupport(registryReturning(List.of()));
        assertThat(empty.mountTools(null)).isNull();
    }

    /** MCP 快照获取异常不影响 DB 工具挂载。 */
    @Test
    void mcpProviderThrows_dbStillMounted() {
        ToolCallback db = namedCallback("db_one");
        McpToolProvider broken = mock(McpToolProvider.class);
        when(broken.getToolCallbacks()).thenThrow(new RuntimeException("manager 爆炸"));
        DefaultToolSupport support = new DefaultToolSupport(registryReturning(List.of(db)), broken);

        ToolMount mount = support.mountTools(null);

        assertThat(mount.callbacks()).containsExactly(db);
    }

    /** AC-38：同轮多工具（DB+MCP）共享同一 requestId 幂等域与 bridge。 */
    @Test
    void multipleCalls_shareRequestIdWithinOneMount() {
        ToolCallback db1 = namedCallback("db_one");
        ToolCallback db2 = namedCallback("db_two");
        ToolCallback mcp1 = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(db1, db2)), providerReturning(mcp1));

        ToolMount mount = support.mountTools("sess-9");

        assertThat(mount.callbacks()).hasSize(3);
        String requestId = (String) mount.toolContext().get("requestId");
        assertThat(requestId).isNotBlank();
    }
}
