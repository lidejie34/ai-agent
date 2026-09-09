package com.dj.ai.agentchat.orchestration.audit.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编排跑次审计表 {@code agent_orchestration_run} 实体（迭代5，T1）。
 *
 * <p>不设外键：run_id 与 {@code agent_tool_call_log.call_id} 前缀自然关联（同一 mount
 * requestId），两表独立生命周期。{@code createdAt} 由 DDL 默认 CURRENT_TIMESTAMP 维护，
 * 插入时留 null。
 */
@Data
@TableName("agent_orchestration_run")
public class OrchestrationRunPO {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 整轮请求 ID（= 工具挂载 requestId；无工具时为编排自生成 UUID）。 */
    private String runId;

    /** 主会话 ID；无状态为 null。 */
    private String sessionId;

    /** Planner 轮次（路由=1；EXECUTOR/SYNTH 为发生时轮次）。 */
    private Integer round;

    /** PLANNER / EXECUTOR / SYNTH。 */
    private String role;

    /** 子任务 ID（PLANNER/SYNTH 为 null）。 */
    private String taskId;

    /** 子任务标题快照。 */
    private String taskTitle;

    /** SUCCESS / FAILED / TIMEOUT / SKIPPED。 */
    private String status;

    /** 调用耗时毫秒。 */
    private Long durationMs;

    /** 本次实际模型 ID。 */
    private String model;

    /** 失败原因：脱敏截断 ≤1000 字，无堆栈无密钥；成功为 null。 */
    private String errorMessage;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime createdAt;
}
