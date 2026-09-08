package com.dj.ai.agentchat.orchestration.support;

import com.dj.ai.agentchat.orchestration.executor.TaskOutcome;
import com.dj.ai.agentchat.orchestration.frame.TaskView;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;

/**
 * 回灌 Planner/Synth 的子任务观察记录（迭代5，T3）：仅含任务标识/标题/终态与
 * <b>截断脱敏后</b>的结果摘要，不含工具原始大文本（AC-54）。Executor 产出的
 * {@link TaskOutcome} 文本已在 ExecutorClient 完成脱敏与 2000 字截断。
 *
 * @param taskId  子任务 ID
 * @param title   子任务标题
 * @param status  succeeded / failed / timeout / skipped
 * @param summary 结果或失败原因摘要
 */
public record ObservationText(String taskId, String title, String status, String summary) {

    public static final String STATUS_TIMEOUT = "timeout";

    public static ObservationText executed(TaskSpec task, TaskOutcome outcome) {
        String status = outcome.timedOut() ? STATUS_TIMEOUT
                : (outcome.ok() ? TaskView.SUCCEEDED : TaskView.FAILED);
        String summary = outcome.ok()
                ? outcome.text()
                : "失败：" + (outcome.error() == null ? "（未提供原因）" : outcome.error());
        return new ObservationText(task.taskId(), task.title(), status, summary);
    }

    public static ObservationText skipped(String taskId, String title) {
        return new ObservationText(taskId, title, TaskView.SKIPPED, "（规划者声明跳过该任务）");
    }

    /** 格式化为再规划/汇总上下文中的一行观察。 */
    public String toLine() {
        return "任务「" + title + "」(" + taskId + ")" + statusLabel() + "：" + summary;
    }

    private String statusLabel() {
        return switch (status) {
            case TaskView.SUCCEEDED -> "成功";
            case TaskView.FAILED -> "失败";
            case STATUS_TIMEOUT -> "超时";
            case TaskView.SKIPPED -> "已跳过";
            default -> status;
        };
    }
}
