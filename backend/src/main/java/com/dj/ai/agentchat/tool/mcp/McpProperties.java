package com.dj.ai.agentchat.tool.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具接入参数（插入迭代4，前缀 {@code app.tools.mcp}）：全部外置可配，缺省值即安全。
 *
 * <p>作为 {@link com.dj.ai.agentchat.tool.ToolProperties} 的嵌套段（{@code @NestedConfigurationProperty}），
 * 受工具总开关 {@code app.tools.enabled} 统管；本子开关 {@code app.tools.mcp.enabled}
 * （默认 true）控制 MCP 运行时 bean 是否装配。{@code servers} 默认空列表 = 不起任何
 * stdio 子进程，行为与迭代 G 逐字节一致（AC-3）。
 *
 * <p>命令/参数/环境全部来自部署配置，代码无任何硬编码命令（AC-5）；command 建议绝对路径，
 * args 为数组形式（ProcessBuilder 直传 argv，<b>不经 shell</b>，禁止 {@code sh -c} 拼接，AC-4）。
 */
@Data
public class McpProperties {

    /** MCP 子开关（默认开启）；false 时 MCP 运行时 bean 不装配、不起子进程，DB 工具不受影响。 */
    private boolean enabled = true;

    /**
     * MCP 握手/协议请求超时（SDK initializationTimeout / requestTimeout 的握手侧）。
     * 默认 20s；工具调用执行超时复用 {@code app.tools.default-timeout-ms}（硬顶 60s）。
     */
    private Duration requestTimeout = Duration.ofSeconds(20);

    /** MCP server 声明列表；默认空 = 零子进程。 */
    @NestedConfigurationProperty
    private List<ServerSpec> servers = new ArrayList<>();

    /**
     * 单个 stdio MCP server 的启动声明。
     *
     * @param name    server 逻辑名，正则 {@code ^[a-z][a-z0-9-]{1,40}$}（工具暴露名前缀）
     * @param command 可执行文件（建议绝对路径，如 /opt/homebrew/bin/npx）
     * @param args    参数数组（直传子进程 argv，不经 shell）
     * @param env     追加环境变量（子进程在继承应用环境基础上叠加，0.14.0 无环境清空能力）
     */
    @Data
    public static class ServerSpec {

        private String name;

        private String command;

        private List<String> args = new ArrayList<>();

        private Map<String, String> env = new HashMap<>();
    }
}
