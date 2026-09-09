package com.dj.ai.agentchat.tool.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计表 {@code agent_tool_call_log} 实体（插入迭代 G）。
 *
 * <p>不设外键：工具行删除后审计行保留（AC-47），{@code toolName} 为字符串留存。
 * {@code callId} 为幂等键（requestId|toolName|sha1(入参JSON)），库内唯一索引兜底；
 * created_at 由 DDL 默认值维护，插入时留 null。
 */
@Data
@TableName("agent_tool_call_log")
public class AgentToolCallLogPO {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 幂等键：requestId|toolName|sha1(入参JSON)。 */
    private String callId;

    /** 工具名（字符串留存，无外键）。 */
    private String toolName;

    /** 处理器类型快照：BUILTIN / SCRIPT。 */
    private String handlerType;

    /** 会话 ID；无状态对话为 null。 */
    private String sessionId;

    /** 入参 JSON：脱敏 + 截断 2000 字。 */
    private String inputSummary;

    /** 执行结果状态：SUCCESS / FAILED / TIMEOUT。 */
    private String status;

    /** 执行耗时毫秒。 */
    private Long durationMs;

    /** 失败/超时原因：脱敏截断 1000 字，无堆栈无密钥；成功为 null。 */
    private String errorMessage;

    /** 实际回传模型的结果字符数（截断 + guide 前缀后）。 */
    private Integer resultChars;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime createdAt;
}
