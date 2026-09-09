package com.dj.ai.agentchat.orchestration.executor;

/**
 * Executor 子任务执行结果（迭代5，T3）。
 *
 * @param ok       任务是否成功（模型 ok:true 契约 / 非 JSON 整段当 result）
 * @param text     成功结果文本（已脱敏 + 截断 maxResultChars，回灌 Planner）；失败为 null
 * @param error    失败原因（已脱敏 + 截断 ≤500 字，帧/观察用）；成功为 null
 * @param durationMs 模型调用耗时（毫秒）
 * @param model    本次实际模型 ID（审计用，可 null）
 * @param timedOut 是否因子任务超时判定失败（AC-47，审计 TIMEOUT）
 */
public record TaskOutcome(boolean ok,
                          String text,
                          String error,
                          long durationMs,
                          String model,
                          boolean timedOut) {

    public static TaskOutcome success(String text, long durationMs, String model) {
        return new TaskOutcome(true, text, null, durationMs, model, false);
    }

    public static TaskOutcome failure(String error, long durationMs, String model, boolean timedOut) {
        return new TaskOutcome(false, null, error, durationMs, model, timedOut);
    }
}
