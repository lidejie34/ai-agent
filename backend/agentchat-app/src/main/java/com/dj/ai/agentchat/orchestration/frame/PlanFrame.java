package com.dj.ai.agentchat.orchestration.frame;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 计划/台账帧（event:plan）：路由轮 round=1 携带初始计划（pending）；
 * 再规划轮 round 递增，tasks 为台账全量最新状态（pending/running/succeeded/failed/skipped）。
 *
 * @param round     Planner 轮次（路由=1）
 * @param tasks     台账全量任务视图
 * @param truncated 初始计划超过 maxTasks 被截断时 true；其余场景 null（省键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanFrame(int round, List<TaskView> tasks, Boolean truncated) implements OrchFrame {
}
