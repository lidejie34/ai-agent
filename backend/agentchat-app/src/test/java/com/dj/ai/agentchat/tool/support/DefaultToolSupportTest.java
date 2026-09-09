package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T8 回归：{@link DefaultToolSupport} 挂载语义。
 *
 * <p>关键护栏：Spring AI {@code DefaultChatClientRequestSpec.toolContext(Map)} 经
 * {@code Assert.noNullElements} 拒绝 null value——无状态对话 sessionId=null 时
 * 上下文不得放 sessionId 键（ChatMemoryConfigTest 全链路曾因此抛
 * IllegalArgumentException → ModelCallException）。
 */
class DefaultToolSupportTest {

    private final ToolRegistry registry = mock(ToolRegistry.class);

    @Test
    void emptyRegistry_returnsNullMount() {
        when(registry.toolCallbacks()).thenReturn(List.of());

        assertThat(new DefaultToolSupport(registry).mountTools(null)).isNull();
    }

    @Test
    void nullCallbacks_returnsNullMount() {
        when(registry.toolCallbacks()).thenReturn(null);

        assertThat(new DefaultToolSupport(registry).mountTools("sess-1")).isNull();
    }

    @Test
    void statelessMount_contextHasNoNullValues_andOmitsSessionIdKey() {
        ToolCallback callback = mock(ToolCallback.class);
        when(registry.toolCallbacks()).thenReturn(List.of(callback));

        ToolMount mount = new DefaultToolSupport(registry).mountTools(null);

        assertThat(mount).isNotNull();
        assertThat(mount.callbacks()).containsExactly(callback);
        assertThat(mount.bridge()).isNotNull();
        // sessionId 为 null：不放键（Spring AI toolContext 拒绝 null value）
        assertThat(mount.toolContext()).doesNotContainKey(DefaultToolSupport.CTX_SESSION_ID);
        assertThat(mount.toolContext().values()).noneMatch(Objects::isNull);
        assertThat(mount.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID)).isInstanceOf(String.class);
        assertThat(mount.toolContext().get(DefaultToolSupport.CTX_TOOL_BRIDGE)).isSameAs(mount.bridge());
    }

    @Test
    void blankSessionId_alsoOmitsSessionIdKey() {
        when(registry.toolCallbacks()).thenReturn(List.of(mock(ToolCallback.class)));

        ToolMount mount = new DefaultToolSupport(registry).mountTools("   ");

        assertThat(mount.toolContext()).doesNotContainKey(DefaultToolSupport.CTX_SESSION_ID);
        assertThat(mount.toolContext().values()).noneMatch(Objects::isNull);
    }

    @Test
    void statefulMount_carriesSessionId_andRequestIdStableAcrossMounts() {
        when(registry.toolCallbacks()).thenReturn(List.of(mock(ToolCallback.class)));
        DefaultToolSupport support = new DefaultToolSupport(registry);

        ToolMount first = support.mountTools("sess-abc");
        ToolMount second = support.mountTools("sess-abc");

        assertThat(first.toolContext().get(DefaultToolSupport.CTX_SESSION_ID)).isEqualTo("sess-abc");
        assertThat(first.toolContext().values()).noneMatch(Objects::isNull);
        // 每次挂载独立 requestId/bridge（请求级隔离）
        assertThat(second.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID))
                .isNotEqualTo(first.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID));
        assertThat(second.bridge()).isNotSameAs(first.bridge());
    }
}
