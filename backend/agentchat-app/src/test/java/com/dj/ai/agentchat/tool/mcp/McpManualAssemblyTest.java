package com.dj.ai.agentchat.tool.mcp;

import com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路径 B 手工装配结构性保证（迭代4 T4，AC-16/AC-46）：
 * 不引入 spring-ai-starter-mcp-client——starter 的自动注册回调/自动配置类
 * * 必须不在 classpath 上 *（否则存在自动注册 SyncMcpToolCallbackProvider、
 * 绕过本工程桥接/审计/脱敏的风险）；触达模型的 MCP 回调仅本工程自造。
 */
class McpManualAssemblyTest {

    @Test
    void starterAutoRegisteredMcpCallback_isNotOnClasspath() {
        assertThatThrownBy(() -> Class.forName("org.springframework.ai.mcp.SyncMcpToolCallback"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void starterMcpAutoConfiguration_isNotOnClasspath() {
        assertThatThrownBy(() -> Class.forName(
                "org.springframework.ai.mcp.autoconfigure.McpClientAutoConfiguration"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void ourMcpProvider_isTheMcpToolCallbackProviderImplementation() {
        // 自造 provider 实现 Spring AI 标准接口，但由 McpRuntimeConfig 显式 @Bean 装配
        assertThat(ToolCallbackProvider.class).isAssignableFrom(McpToolProvider.class);
        Package pkg = McpToolProvider.class.getPackage();
        assertThat(pkg.getName()).startsWith("com.dj.ai.agentchat.tool.mcp");
    }
}
