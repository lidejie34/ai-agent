package com.dj.ai.agentchat.orchestration.planner;

/**
 * 一个子任务规格（Planner 产出，Executor 消费）。
 *
 * @param taskId 计划内唯一短标识（缺失/非法由编排按序补 {@code t1..tN}）
 * @param title  ≤40 字短标题（帧/审计展示）
 * @param goal   目标描述（可含对前序任务的显式依赖，如「基于 t1 的错误列表」）
 */
public record TaskSpec(String taskId, String title, String goal) {
}
