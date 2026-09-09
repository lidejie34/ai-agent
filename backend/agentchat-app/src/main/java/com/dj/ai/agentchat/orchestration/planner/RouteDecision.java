package com.dj.ai.agentchat.orchestration.planner;

import java.util.List;

/**
 * Planner 路由轮（第 1 次调用）解析结果（迭代5，T2）。
 *
 * <ul>
 *   <li>{@link Direct}：闲聊/单轮常识，{@code answer} 直接拆帧推送（模型调用恰 1 次）；</li>
 *   <li>{@link Plan}：复杂请求，{@code tasks} 为截断到 maxTasks 后的有序子任务，
 *       {@code truncated} 标记原始计划是否被截断（AC-13）；</li>
 *   <li>{@link Unparseable}：无法解析为契约 JSON（重试 1 次后仍失败 → 降级迭代4 直答）。</li>
 * </ul>
 */
public sealed interface RouteDecision {

    record Direct(String answer) implements RouteDecision {
    }

    record Plan(List<TaskSpec> tasks, boolean truncated) implements RouteDecision {
    }

    record Unparseable() implements RouteDecision {
    }
}
