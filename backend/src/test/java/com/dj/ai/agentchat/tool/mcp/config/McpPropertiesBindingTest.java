package com.dj.ai.agentchat.tool.mcp.config;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：{@link McpProperties} 绑定与缺省值（不走 Spring 上下文，避免任何子进程副作用）。
 * 覆盖 AC-3（默认空 server 列表）/AC-5（命令参数全外置）。
 */
class McpPropertiesBindingTest {

    @Test
    void defaults_areSafe_emptyServers() {
        McpProperties props = new McpProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(props.getServers()).isEmpty();
    }

    @Test
    void binds_servers_args_env_andTimeout() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("request-timeout", "15s");
        source.put("servers[0].name", "everything");
        source.put("servers[0].command", "/opt/homebrew/bin/npx");
        source.put("servers[0].args[0]", "-y");
        source.put("servers[0].args[1]", "@modelcontextprotocol/server-everything");
        source.put("servers[0].env.DEBUG", "mcp");
        source.put("servers[1].name", "fs");
        source.put("servers[1].command", "/usr/local/bin/node");
        source.put("servers[1].args[0]", "/opt/npx-cli.js");

        McpProperties props = new Binder(source).bind("", McpProperties.class)
                .orElseGet(McpProperties::new);

        assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.getServers()).hasSize(2);

        McpProperties.ServerSpec everything = props.getServers().get(0);
        assertThat(everything.getName()).isEqualTo("everything");
        assertThat(everything.getCommand()).isEqualTo("/opt/homebrew/bin/npx");
        assertThat(everything.getArgs()).containsExactly("-y", "@modelcontextprotocol/server-everything");
        assertThat(everything.getEnv()).containsEntry("DEBUG", "mcp");

        McpProperties.ServerSpec fs = props.getServers().get(1);
        assertThat(fs.getName()).isEqualTo("fs");
        assertThat(fs.getCommand()).isEqualTo("/usr/local/bin/node");
        assertThat(fs.getArgs()).containsExactly("/opt/npx-cli.js");
    }

    @Test
    void binds_millisTimeout_asDuration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("request-timeout", "20000ms");
        McpProperties props = new Binder(source).bind("", McpProperties.class)
                .orElseGet(McpProperties::new);
        assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofMillis(20000));
    }
}
