package com.dj.ai.agentchat.orchestration.support;

/**
 * 编排触顶原因常量（迭代5，T4）：强制收尾时注入 Synth 用户消息（{@code SddPrompts.forceFinishNote}），
 * 并在 info 日志与审计中体现。
 */
public final class CapReasons {

    private CapReasons() {
    }

    /** Planner 调用轮次达 maxRounds（AC-42）。 */
    public static final String ROUNDS = "规划轮次上限";
    /** 累计子任务数达 maxTasks（AC-43）。 */
    public static final String TASKS = "子任务总数上限";
    /** 连续失败数达 maxConsecutiveFailures（AC-44）。 */
    public static final String FAILURES = "连续失败上限";
    /** 总墙钟预算耗尽（AC-45）。 */
    public static final String BUDGET = "总耗时预算";
    /** 重复规划检测：同标题归一化且前任务 failed，累计 2 次（AC-46）。 */
    public static final String LOOP = "重复规划（疑似死循环）";
    /** 再规划输出两次均无法解析（强制收尾，非降级）。 */
    public static final String REPLAN_PARSE = "再规划输出无法解析";

    /** Synth 前剩余时间不足以发起收尾调用（AC-45：收尾失败 → error/502）。 */
    public static final String BUDGET_NO_SYNTH = "总耗时预算耗尽且收尾时间不足";
}
