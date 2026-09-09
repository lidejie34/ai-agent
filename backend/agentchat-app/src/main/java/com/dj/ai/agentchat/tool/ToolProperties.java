package com.dj.ai.agentchat.tool;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具能力参数（插入迭代 G，前缀 {@code app.tools}）：全部外置可配，缺省值即安全。
 *
 * <p>{@code enabled=false} 时工具运行时全家桶（Mapper/注册中心/处理器/回调/审计）
 * 不装配、对话零工具挂载；管理端 web 层常驻但被拦截器挡为 400 {@code TOOLS_DISABLED}。
 */
@Data
@ConfigurationProperties(prefix = "app.tools")
public class ToolProperties {

    /** 工具总开关（默认开启）；false 时 ToolRuntimeConfig 整包不装配。 */
    private boolean enabled = true;

    /** SCRIPT 工具白名单根目录（相对 CWD；生产建议只读挂载的绝对路径）。 */
    private String scriptDir = "scripts";

    /** 工具执行专用 daemon 线程池大小。 */
    private int executorPoolSize = 4;

    /** 表行 timeout_ms 缺省值（毫秒）；硬上限 60000，管理端校验拦截超限值。 */
    private int defaultTimeoutMs = 30000;

    /** 表行 output_max_chars 缺省值（回传模型结果最大字符数）。 */
    private int defaultOutputMaxChars = 8000;

    /** 增补脱敏正则（启动编译为 Pattern，编译失败仅 WARN 跳过该条）。 */
    private List<String> redactPatterns = new ArrayList<>();

    /** 内置日志分析工具参数。 */
    private Builtin builtin = new Builtin();

    /**
     * 迭代4：MCP 工具接入参数（{@code app.tools.mcp.*}）。默认 enabled=true、servers 空列表
     * （零子进程，行为与迭代 G 一致）；MCP 运行时 bean 另受 mcp.enabled 子开关控制。
     */
    @NestedConfigurationProperty
    private McpProperties mcp = new McpProperties();

    @Data
    public static class Builtin {

        /** 日志分析根目录（相对 CWD；强烈建议配绝对路径，mvn CWD=backend/ 与 nohup CWD 可能不同）。 */
        private String logDir = "logs";

        /** 单次扫描常规文件数上限，触顶停止并标注 truncated。 */
        private int scanMaxFiles = 200;

        /** 单文件读取字节上限（默认 50MB），触顶停止读取该文件并标注 truncated。 */
        private long scanMaxBytesPerFile = 52428800L;

        /** 单次扫描累计日志行上限，触顶停止并标注 truncated。 */
        private int scanMaxLines = 200000;
    }
}
