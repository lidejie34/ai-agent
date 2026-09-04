package com.dj.ai.agentchat.tool.handler;

import com.dj.ai.agentchat.tool.callback.SkippableToolException;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3：处理器路由——已实现类型正常路由；预留类型（HTTP/SCRIPT_DB）抛 SkippableToolException
 * 供注册中心跳过（AC-16）。
 */
class ToolHandlerRouterTest {

    static class StubBuiltinHandler implements ToolHandler {
        @Override
        public HandlerType type() {
            return HandlerType.BUILTIN;
        }

        @Override
        public void validateConfig(String handlerConfigJson) {
        }

        @Override
        public ToolExecutionResult execute(AgentToolPO tool, Map<String, Object> args,
                                           ToolExecutionContext ctx) {
            return ToolExecutionResult.success("ok");
        }
    }

    @Test
    void route_implementedType_returnsHandler() {
        StubBuiltinHandler handler = new StubBuiltinHandler();
        ToolHandlerRouter router = new ToolHandlerRouter(List.of(handler));

        assertThat(router.route(HandlerType.BUILTIN)).isSameAs(handler);
        assertThat(router.supports(HandlerType.BUILTIN)).isTrue();
    }

    @Test
    void route_reservedType_throwsSkippable() {
        ToolHandlerRouter router = new ToolHandlerRouter(List.of(new StubBuiltinHandler()));

        assertThat(router.supports(HandlerType.HTTP)).isFalse();
        assertThat(router.supports(HandlerType.SCRIPT_DB)).isFalse();
        assertThatThrownBy(() -> router.route(HandlerType.HTTP))
                .isInstanceOf(SkippableToolException.class)
                .hasMessageContaining("HTTP");
        assertThatThrownBy(() -> router.route(HandlerType.SCRIPT_DB))
                .isInstanceOf(SkippableToolException.class);
    }

    @Test
    void route_emptyHandlers_allTypesSkippable() {
        ToolHandlerRouter router = new ToolHandlerRouter(List.of());
        assertThatThrownBy(() -> router.route(HandlerType.BUILTIN))
                .isInstanceOf(SkippableToolException.class);
    }
}
