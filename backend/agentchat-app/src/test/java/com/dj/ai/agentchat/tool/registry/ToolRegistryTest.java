package com.dj.ai.agentchat.tool.registry;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dj.ai.agentchat.exception.ToolsUnavailableException;
import com.dj.ai.agentchat.tool.callback.SkippableToolException;
import com.dj.ai.agentchat.tool.callback.ToolCallbackFactory;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.schema.ToolSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3：注册中心装载/缓存/刷新/坏行跳过/DB 故障降级与自愈（AC-10~16）。
 */
class ToolRegistryTest {

    private AgentToolMapper mapper;
    private ToolCallbackFactory factory;
    private ToolSchemaInitializer initializer;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentToolMapper.class);
        factory = mock(ToolCallbackFactory.class);
        initializer = mock(ToolSchemaInitializer.class);
        registry = new ToolRegistry(mapper, factory, initializer);
    }

    private AgentToolPO row(long id, String name, String handlerType) {
        AgentToolPO po = new AgentToolPO();
        po.setId(id);
        po.setToolName(name);
        po.setHandlerType(handlerType);
        po.setEnabled(true);
        return po;
    }

    @Test
    void toolCallbacks_loadsEnabledRows_andBuildsCallbacks() {
        AgentToolPO row1 = row(1, "demo_builtin_tool", "BUILTIN");
        AgentToolPO row2 = row(2, "demo_script_tool", "SCRIPT");
        when(mapper.selectList(any())).thenReturn(List.of(row1, row2));
        ToolCallback cb1 = mock(ToolCallback.class);
        ToolCallback cb2 = mock(ToolCallback.class);
        when(factory.build(row1)).thenReturn(cb1);
        when(factory.build(row2)).thenReturn(cb2);

        List<ToolCallback> callbacks = registry.toolCallbacks();

        assertThat(callbacks).containsExactly(cb1, cb2);
        // 懒建表在装载前执行
        verify(initializer).ensureSchema();
        // 查询条件：enabled=1，按 id 升序
        ArgumentCaptor<QueryWrapper<AgentToolPO>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("enabled");
    }

    @Test
    void toolCallbacks_secondCall_usesVolatileSnapshot_zeroDb() {
        when(mapper.selectList(any())).thenReturn(List.of());

        registry.toolCallbacks();
        registry.toolCallbacks();

        // 热缓存：第二次零 DB（AC-12）
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void badRow_isSkippedWithWarn_otherRowsStillLoad() {
        AgentToolPO good = row(1, "demo_builtin_tool", "BUILTIN");
        AgentToolPO bad = row(2, "future_tool", "HTTP");
        when(mapper.selectList(any())).thenReturn(List.of(good, bad));
        ToolCallback cb = mock(ToolCallback.class);
        when(factory.build(good)).thenReturn(cb);
        when(factory.build(bad)).thenThrow(new SkippableToolException("处理器类型尚未实现: HTTP"));

        List<ToolCallback> callbacks = registry.toolCallbacks();

        assertThat(callbacks).containsExactly(cb);
    }

    @Test
    void dbFailure_degradesToEmpty_andDoesNotCacheFailure_selfHeals() {
        // 第一次装载 DB 故障 → 空集 + WARN（AC-13）
        when(mapper.selectList(any()))
                .thenThrow(new CannotGetJdbcConnectionException("连接拒绝"))
                .thenReturn(List.of());

        List<ToolCallback> first = registry.toolCallbacks();
        assertThat(first).isEmpty();

        // 失败不缓存：DB 恢复后再次调用重新装载（自愈）
        List<ToolCallback> second = registry.toolCallbacks();
        assertThat(second).isEmpty();
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    void refresh_success_updatesSnapshot() {
        AgentToolPO row = row(1, "demo_builtin_tool", "BUILTIN");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        ToolCallback cb = mock(ToolCallback.class);
        when(factory.build(row)).thenReturn(cb);

        registry.refresh();

        // refresh 后对话路径零 DB 命中新快照
        assertThat(registry.toolCallbacks()).containsExactly(cb);
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void refresh_dbFailure_throws503() {
        when(mapper.selectList(any())).thenThrow(new CannotGetJdbcConnectionException("DB down"));

        assertThatThrownBy(registry::refresh)
                .isInstanceOf(ToolsUnavailableException.class);
    }

    @Test
    void schemaFailure_onConversationPath_degradesEmpty() {
        // 懒建表失败（DataAccessException）同样降级空集，不抛到对话链路
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("建表失败"))
                .when(initializer).ensureSchema();

        assertThat(registry.toolCallbacks()).isEmpty();
        verify(mapper, never()).selectList(any());
    }
}
