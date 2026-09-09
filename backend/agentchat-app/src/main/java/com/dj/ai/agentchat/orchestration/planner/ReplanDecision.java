package com.dj.ai.agentchat.orchestration.planner;

import java.util.List;

/**
 * Planner 再规划轮（每个子任务终态后）解析结果（迭代5，T2）。
 *
 * <ul>
 *   <li>{@link Next}：还需执行一个新子任务（同一时刻只给一个）；
 *       {@code skippedTaskIds} 为 Planner 声明不再执行的既有 pending 任务；</li>
 *   <li>{@link Final}：信息已足够，进入汇总；</li>
 *   <li>{@link Unparseable}：无法解析（重试 1 次后仍失败 → 强制收尾）。</li>
 * </ul>
 */
public sealed interface ReplanDecision {

    record Next(TaskSpec task, List<String> skippedTaskIds) implements ReplanDecision {
    }

    record Final() implements ReplanDecision {
    }

    record Unparseable() implements ReplanDecision {
    }
}
