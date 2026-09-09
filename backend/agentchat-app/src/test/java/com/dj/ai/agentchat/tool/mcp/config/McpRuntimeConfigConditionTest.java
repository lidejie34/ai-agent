package com.dj.ai.agentchat.tool.mcp.config;

import com.dj.ai.agentchat.tool.ToolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：MCP 双开关条件装配与配置绑定（AC-1/2/3/5）。
 *
 * <p>三态：默认（双开关 matchIfMissing=true）MCP 配置段与 McpRuntimeConfig 装配、
 * servers 空列表（零子进程，与迭代 G 一致）；子开关 mcp.enabled=false 时配置段仍绑定
 * 但 MCP 运行时 bean 缺席（T2 起补 manager 断言）；总开关 tools.enabled=false 时
 * McpRuntimeConfig 整包不装配。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class McpRuntimeConfigConditionTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void mcpProperties_boundByDefault_withSafeDefaults() {
        ToolProperties props = context.getBean(ToolProperties.class);
        assertThat(props.getMcp()).isNotNull();
        assertThat(props.getMcp().isEnabled()).isTrue();
        assertThat(props.getMcp().getRequestTimeout()).isEqualTo(Duration.ofSeconds(20));
        // 默认空 server 列表：不起任何子进程（AC-3）
        assertThat(props.getMcp().getServers()).isEmpty();
    }

    @Test
    void mcpRuntimeConfig_presentByDefault() {
        assertThat(context.getBeansOfType(McpRuntimeConfig.class)).isNotEmpty();
    }
}

/**
 * 子开关关闭：app.tools.mcp.enabled=false —— 配置段仍绑定（值为 false），
 * McpRuntimeConfig 类仍在（总开关开），但其内 MCP 运行时 bean 不装配（T2 起断言）。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.tools.mcp.enabled=false"
})
class McpSubSwitchDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void mcpProperties_boundButDisabled() {
        ToolProperties props = context.getBean(ToolProperties.class);
        assertThat(props.getMcp().isEnabled()).isFalse();
    }

    @Test
    void mcpRuntimeConfig_stillPresent_whenOnlySubSwitchOff() {
        // 类级条件只看总开关；子开关由 bean 级 @ConditionalOnProperty 收口
        assertThat(context.getBeansOfType(McpRuntimeConfig.class)).isNotEmpty();
    }
}

/**
 * 总开关关闭：app.tools.enabled=false —— McpRuntimeConfig 整包不装配（AC-1）。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.tools.enabled=false"
})
class McpTotalSwitchDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void mcpRuntimeConfig_absent_whenTotalSwitchOff() {
        assertThat(context.getBeansOfType(McpRuntimeConfig.class)).isEmpty();
    }
}
