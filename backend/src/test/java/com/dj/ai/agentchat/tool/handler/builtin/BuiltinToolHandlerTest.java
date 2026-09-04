package com.dj.ai.agentchat.tool.handler.builtin;

import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T5：BuiltinToolHandler——按 handler_config.bean 路由；装载期校验坏行抛
 * InvalidChatRequestException；运行期缺失/逃逸全部结构化失败不上浮。
 */
class BuiltinToolHandlerTest {

    private final ToolExecutionContext ctx = new ToolExecutionContext(null, null, 30000, 8000);

    private AgentToolPO tool(String config) {
        AgentToolPO po = new AgentToolPO();
        po.setToolName("t");
        po.setHandlerType("BUILTIN");
        po.setHandlerConfig(config);
        return po;
    }

    @Test
    void type_isBuiltin() {
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of());
        assertThat(handler.type()).isEqualTo(HandlerType.BUILTIN);
    }

    @Test
    void validateConfig_knownBean_passes() {
        BuiltinTool stub = mock(BuiltinTool.class);
        when(stub.key()).thenReturn("analyzeLogErrors");
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of(stub));

        handler.validateConfig("{\"bean\":\"analyzeLogErrors\"}");
    }

    @Test
    void validateConfig_unknownBean_throws() {
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of());

        assertThatThrownBy(() -> handler.validateConfig("{\"bean\":\"ghost\"}"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void validateConfig_missingBean_throws() {
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of());

        assertThatThrownBy(() -> handler.validateConfig("{\"other\":1}"))
                .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> handler.validateConfig("not-json"))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void execute_routesToBeanByKey_andReturnsItsResult() {
        BuiltinTool stub = mock(BuiltinTool.class);
        when(stub.key()).thenReturn("stubTool");
        when(stub.execute(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolExecutionResult.success("stub-markdown"));
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of(stub));

        ToolExecutionResult result = handler.execute(
                tool("{\"bean\":\"stubTool\"}"), Map.of("minutes", 30), ctx);

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("stub-markdown");
    }

    @Test
    void execute_unknownBeanAtRuntime_returnsStructuredFailure() {
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of());

        ToolExecutionResult result = handler.execute(
                tool("{\"bean\":\"ghost\"}"), Map.of(), ctx);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_NOT_AVAILABLE");
    }

    @Test
    void execute_beanThrowing_returnsStructuredFailure_noEscape() {
        BuiltinTool broken = mock(BuiltinTool.class);
        when(broken.key()).thenReturn("broken");
        when(broken.execute(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("内置工具炸了"));
        BuiltinToolHandler handler = new BuiltinToolHandler(List.of(broken));

        ToolExecutionResult result = handler.execute(
                tool("{\"bean\":\"broken\"}"), Map.of(), ctx);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
        assertThat(result.errorMessage()).contains("内置工具炸了");
    }
}
