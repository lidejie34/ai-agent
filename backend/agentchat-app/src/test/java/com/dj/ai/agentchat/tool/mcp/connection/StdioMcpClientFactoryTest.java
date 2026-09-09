package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StdioMcpClientFactory 失败路径单测（迭代4 T2，AC-7）：
 * 命令不存在 → ProcessBuilder.start 失败 → initialize 抛错 → 包装为
 * {@link McpConnectException}（不启动任何真实 MCP server，进程根本未 spawn）。
 */
class StdioMcpClientFactoryTest {

    @Test
    void connect_nonexistentCommand_throwsMcpConnectException() {
        StdioMcpClientFactory factory = new StdioMcpClientFactory();
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName("ghost");
        spec.setCommand("/nonexistent/dj-mcp-no-such-bin-xyz");
        spec.setArgs(new ArrayList<>());

        assertThatThrownBy(() -> factory.connect(spec, Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(McpConnectException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("握手失败");
    }

    @Test
    void mcpConnectException_isRuntimeException() {
        assertThat(new McpConnectException("x", null)).isInstanceOf(RuntimeException.class);
    }
}
