package com.dj.ai.agentchat.orchestration.frame;

/**
 * 计划台账中的子任务视图（迭代5，T3/T4）：plan 帧 tasks 数组元素。
 *
 * @param taskId 计划内唯一短标识
 * @param title  短标题
 * @param status 五态：{@link #PENDING}/{@link #RUNNING}/{@link #SUCCEEDED}/
 *               {@link #FAILED}/{@link #SKIPPED}
 */
public record TaskView(String taskId, String title, String status) {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public TaskView withStatus(String newStatus) {
        return new TaskView(taskId, title, newStatus);
    }
}
