package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultToolSupport 对话级选择矩阵（迭代12 D4，FR-1）：
 * selection 各字段三态——null=该侧全量（与迭代11 逐字节一致）、[]=该侧全不挂、非空=子集；
 * DB 按工具名过滤，MCP 经 {@code getToolCallbacks(Set)} 按 server key 源头过滤；
 * 两侧皆空 → null 挂载（不触发 .tools()）。
 */
class DefaultToolSupportSelectionTest {

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
        when(provider.getToolCallbacks(any(Set.class))).thenAnswer(inv -> {
            // 模拟源头过滤：只返回被选中 server 的回调（测试回调名即 server 名）
            Set<?> names = inv.getArgument(0);
            return java.util.Arrays.stream(callbacks)
                    .filter(cb -> names.contains(cb.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
        });
        return provider;
    }

    /** selection=null：全量挂载，与单参重载逐字节一致。 */
    @Test
    void selectionNull_mountsAll() {
        ToolCallback db = namedCallback("db_one");
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(db)), providerReturning(mcp));

        ToolMount mount = support.mountTools("sess-1", null);

        assertThat(mount).isNotNull();
        assertThat(mount.callbacks()).containsExactly(db, mcp);
    }

    /** 两字段均 null：等价 selection=null（全量）。 */
    @Test
    void bothFieldsNull_mountsAll() {
        ToolCallback db = namedCallback("db_one");
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(db)), providerReturning(mcp));

        ToolMount mount = support.mountTools(null, new ToolSelection(null, null));

        assertThat(mount.callbacks()).containsExactly(db, mcp);
    }

    /** DB 子集：按工具名过滤；MCP 侧未选择（null）→ 全量。 */
    @Test
    void toolNamesSubset_filtersDbByName_mcpUntouched() {
        ToolCallback a = namedCallback("db_a");
        ToolCallback b = namedCallback("db_b");
        ToolCallback c = namedCallback("db_c");
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(a, b, c)), providerReturning(mcp));

        ToolMount mount = support.mountTools(null, new ToolSelection(List.of("db_a", "db_c"), null));

        assertThat(mount.callbacks()).containsExactly(a, c, mcp);
    }

    /** DB 空列表：该侧全不挂（连注册中心都不查询）；MCP 全量。 */
    @Test
    void toolNamesEmpty_skipsDbSideEntirely() {
        ToolRegistry registry = registryReturning(List.of(namedCallback("db_a")));
        ToolCallback mcp = namedCallback("fs_echo");
        DefaultToolSupport support = new DefaultToolSupport(registry, providerReturning(mcp));

        ToolMount mount = support.mountTools(null, new ToolSelection(List.of(), null));

        assertThat(mount.callbacks()).containsExactly(mcp);
        verify(registry, never()).toolCallbacks();
    }

    /** MCP 子集：走 server key 源头过滤变体（不走扁平快照）。 */
    @Test
    void mcpServersSubset_usesSetVariant_dbUntouched() {
        ToolCallback db = namedCallback("db_one");
        ToolCallback fs = namedCallback("fs");
        ToolCallback ev = namedCallback("ev");
        McpToolProvider provider = providerReturning(fs, ev);
        DefaultToolSupport support = new DefaultToolSupport(registryReturning(List.of(db)), provider);

        ToolMount mount = support.mountTools(null, new ToolSelection(null, List.of("fs")));

        assertThat(mount.callbacks()).containsExactly(db, fs);
        verify(provider).getToolCallbacks(Set.of("fs"));
        verify(provider, never()).getToolCallbacks();
    }

    /** MCP 空列表：该侧全不挂（零调用）；DB 全量。 */
    @Test
    void mcpServersEmpty_skipsMcpSideEntirely() {
        ToolCallback db = namedCallback("db_one");
        McpToolProvider provider = providerReturning(namedCallback("fs"));
        DefaultToolSupport support = new DefaultToolSupport(registryReturning(List.of(db)), provider);

        ToolMount mount = support.mountTools(null, new ToolSelection(null, List.of()));

        assertThat(mount.callbacks()).containsExactly(db);
        verify(provider, never()).getToolCallbacks();
        verify(provider, never()).getToolCallbacks(any(Set.class));
    }

    /** 两侧皆空列表（前端「启用工具」关）→ null 挂载：调用方不触发 .tools()。 */
    @Test
    void bothEmpty_returnsNullMount() {
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(namedCallback("db_a"))),
                providerReturning(namedCallback("fs")));

        assertThat(support.mountTools("sess-1", new ToolSelection(List.of(), List.of()))).isNull();
    }

    /** 未知名自然落空：DB 未知名不在快照中，MCP 未知 server 源头落空 → null 挂载。 */
    @Test
    void unknownNames_fallThroughToNullMount() {
        DefaultToolSupport support = new DefaultToolSupport(
                registryReturning(List.of(namedCallback("db_a"))),
                providerReturning(namedCallback("fs")));

        ToolMount mount = support.mountTools(null,
                new ToolSelection(List.of("no_such_tool"), List.of("no_such_server")));

        assertThat(mount).isNull();
    }
}
