package com.dj.ai.agentchat.orchestration;

/**
 * 同步编排结果（迭代5，T4）：reply 为最终答案纯文本（direct 答案或 synth 聚合/降级回复），
 * ChatService 成对落库后包装为 ChatResponse（model 为空时回退主配置模型）。
 *
 * @param reply 最终答案纯文本
 * @param model 实际模型 ID（可空=由 ChatService 回退配置模型）
 */
public record OrchSyncOutcome(String reply, String model) {
}
