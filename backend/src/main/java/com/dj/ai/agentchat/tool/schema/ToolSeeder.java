package com.dj.ai.agentchat.tool.schema;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 工具种子器（插入迭代 G）：幂等补齐两个首发工具行（AC-8/9）。
 *
 * <ul>
 *   <li>{@code analyze_log_errors}：BUILTIN（bean=analyzeLogErrors），enabled=true，
 *       指南取 classpath {@code skills/analyze_log_errors.md}；</li>
 *   <li>{@code log_error_count}：SCRIPT（script=log_error_count.sh）示例能力，
 *       <b>enabled=false</b>（D4：脚本能力默认关闭，管理端显式启用），
 *       指南取 classpath {@code skills/log_error_count.md}。</li>
 * </ul>
 *
 * <p>种子只补缺失（selectCount 判断），已存在一律不 UPDATE——不覆盖管理端修改（AC-7）。
 * guide 资源读取失败仅 WARN 且 guide_md 置 null，不阻断种子。DB 异常原样上抛
 * DataAccessException，由调用方 best-effort 吞掉（Runner WARN；懒装载路径降级空集）。
 */
@Slf4j
public class ToolSeeder {

    /** 内置日志分析工具名（function calling name）。 */
    public static final String BUILTIN_TOOL_NAME = "analyze_log_errors";
    /** SCRIPT 示例工具名（默认禁用）。 */
    public static final String SCRIPT_TOOL_NAME = "log_error_count";

    static final String BUILTIN_BEAN_KEY = "analyzeLogErrors";
    static final String SCRIPT_FILE_NAME = "log_error_count.sh";

    static final String BUILTIN_HANDLER_CONFIG = "{\"bean\":\"" + BUILTIN_BEAN_KEY + "\"}";
    static final String SCRIPT_HANDLER_CONFIG = "{\"script\":\"" + SCRIPT_FILE_NAME + "\"}";

    static final String BUILTIN_INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "minutes": {"type": "integer", "minimum": 1, "maximum": 1440,
                            "description": "回溯时间窗（分钟），必填，1-1440"},
                "fileName": {"type": "string", "pattern": "^[A-Za-z0-9._-]+$",
                             "description": "可选：仅分析日志目录内该文件名（不接受路径）"},
                "topN": {"type": "integer", "minimum": 1, "maximum": 50,
                         "description": "可选：异常分组 Top N，默认 10"}
              },
              "required": ["minutes"]
            }
            """;

    static final String SCRIPT_INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "minutes": {"type": "integer", "minimum": 1, "maximum": 1440,
                            "description": "回溯时间窗（分钟），必填，1-1440"}
              },
              "required": ["minutes"]
            }
            """;

    static final String BUILTIN_GUIDE_LOCATION = "skills/analyze_log_errors.md";
    static final String SCRIPT_GUIDE_LOCATION = "skills/log_error_count.md";

    private final AgentToolMapper toolMapper;

    public ToolSeeder(AgentToolMapper toolMapper) {
        this.toolMapper = toolMapper;
    }

    /**
     * 幂等种子：缺行才插，已存在跳过。DB 异常上抛 DataAccessException 由调用方兜底。
     */
    public void seedIfAbsent() {
        seedBuiltin();
        seedScript();
    }

    private void seedBuiltin() {
        if (exists(BUILTIN_TOOL_NAME)) {
            return;
        }
        AgentToolPO po = new AgentToolPO();
        po.setToolName(BUILTIN_TOOL_NAME);
        po.setDescription("分析日志目录中指定时间窗内的 ERROR/WARN 日志与异常堆栈："
                + "级别计数、异常分组 TopN、代表性堆栈片段。用户要求排查/分析日志错误时调用。");
        po.setInputSchema(BUILTIN_INPUT_SCHEMA);
        po.setHandlerType("BUILTIN");
        po.setHandlerConfig(BUILTIN_HANDLER_CONFIG);
        po.setGuideMd(loadGuide(BUILTIN_GUIDE_LOCATION));
        po.setEnabled(true);
        po.setTimeoutMs(30000);
        po.setOutputMaxChars(8000);
        toolMapper.insert(po);
        log.info("种子工具已插入: {}（BUILTIN, enabled=true）", BUILTIN_TOOL_NAME);
    }

    private void seedScript() {
        if (exists(SCRIPT_TOOL_NAME)) {
            return;
        }
        AgentToolPO po = new AgentToolPO();
        po.setToolName(SCRIPT_TOOL_NAME);
        po.setDescription("示例脚本工具：统计日志目录中 ERROR/WARN 行数（白名单脚本 "
                + SCRIPT_FILE_NAME + "）。默认禁用，需管理端审计后显式启用。");
        po.setInputSchema(SCRIPT_INPUT_SCHEMA);
        po.setHandlerType("SCRIPT");
        po.setHandlerConfig(SCRIPT_HANDLER_CONFIG);
        po.setGuideMd(loadGuide(SCRIPT_GUIDE_LOCATION));
        // D4：SCRIPT 示例行默认禁用——脚本能力需人工审计后开启
        po.setEnabled(false);
        po.setTimeoutMs(30000);
        po.setOutputMaxChars(4000);
        toolMapper.insert(po);
        log.info("种子工具已插入: {}（SCRIPT, enabled=false 默认禁用）", SCRIPT_TOOL_NAME);
    }

    private boolean exists(String toolName) {
        Long count = toolMapper.selectCount(
                new QueryWrapper<AgentToolPO>().eq("tool_name", toolName));
        return count != null && count > 0;
    }

    /**
     * 读取 classpath 指南全文；资源不存在/读取失败仅 WARN 返回 null（不阻断种子）。
     */
    static String loadGuide(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            log.warn("种子指南资源不存在，guide_md 置 null: {}", classpathLocation);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("种子指南读取失败，guide_md 置 null: {}, 原因={}", classpathLocation, e.getMessage());
            return null;
        }
    }
}
