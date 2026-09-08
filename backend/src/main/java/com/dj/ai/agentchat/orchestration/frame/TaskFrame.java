package com.dj.ai.agentchat.orchestration.frame;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 子任务状态帧（event:task）：同一 taskId 的 started 严格先于终态；
 * started 带 round；succeeded 带 durationMs；failed 带 error（脱敏截断 ≤500 字）。
 *
 * @param taskId     子任务 ID
 * @param title      子任务标题
 * @param status     {@link #STARTED} / {@link #SUCCEEDED} / {@link #FAILED}
 * @param round      所属 Planner 轮次（仅 started 帧）
 * @param durationMs 执行耗时毫秒（仅 succeeded 帧）
 * @param error      失败原因（仅 failed 帧，≤500 字、脱敏、无堆栈）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskFrame(String taskId, String title, String status,
                        Integer round, Long durationMs, String error) implements OrchFrame {

    public static final String STARTED = "started";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    public static TaskFrame started(String taskId, String title, int round) {
        return new TaskFrame(taskId, title, STARTED, round, null, null);
    }

    public static TaskFrame succeeded(String taskId, String title, long durationMs) {
        return new TaskFrame(taskId, title, SUCCEEDED, null, durationMs, null);
    }

    public static TaskFrame failed(String taskId, String title, String error) {
        return new TaskFrame(taskId, title, FAILED, null, null, error);
    }
}
