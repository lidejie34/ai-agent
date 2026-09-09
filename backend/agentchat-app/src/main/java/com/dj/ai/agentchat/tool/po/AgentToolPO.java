package com.dj.ai.agentchat.tool.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具注册表 {@code agent_tool} 实体（插入迭代 G）。
 *
 * <p>{@code inputSchema} / {@code handlerConfig} 对应 MySQL JSON 列，PO 以 String 持有、
 * fastjson2 代码内解析（不写 TypeHandler）；input_schema 逐字透传给
 * {@code DefaultToolDefinition.inputSchema()}（AC-10）。
 * {@code enabled} 用 Boolean 映射 TINYINT(1)；created_at/updated_at 由 DDL 默认值与
 * ON UPDATE 维护，插入时留 null（MP NOT_NULL 策略不进 INSERT 列）。
 */
@Data
@TableName("agent_tool")
public class AgentToolPO {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 工具名（function calling name），唯一。 */
    private String toolName;

    /** 工具描述（模型据此判断调用时机）。 */
    private String description;

    /** 入参 JSON Schema 文本（逐字成为 ToolDefinition.inputSchema）。 */
    private String inputSchema;

    /** 处理器类型：BUILTIN / SCRIPT（HTTP / SCRIPT_DB 预留不挂载）。 */
    private String handlerType;

    /** 处理器配置 JSON 文本：BUILTIN {"bean":"..."}；SCRIPT {"script":"文件名"}。 */
    private String handlerConfig;

    /** SKILL.md 式操作指南全文（装载时逐字读 DB，调用时前置到工具结果；可为 null）。 */
    private String guideMd;

    /** 是否启用挂载。 */
    private Boolean enabled;

    /** 单次执行超时毫秒（缺省 30000，硬上限 60000）。 */
    private Integer timeoutMs;

    /** 回传模型结果最大字符数（缺省 8000）。 */
    private Integer outputMaxChars;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime createdAt;

    /** 更新时间（DB ON UPDATE CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime updatedAt;
}
