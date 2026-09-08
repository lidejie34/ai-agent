package com.dj.ai.agentchat.orchestration.planner;

import com.dj.ai.agentchat.orchestration.support.ObservationText;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次编排跑次中 Planner/Synth 角色调用的上下文（迭代5，T3）。
 *
 * <p>编排层不注入 ConversationStore：主会话历史由 ChatService 以
 * {@code List<Message>} 传入（AC-20）；跑次标识 runId 贯穿审计与日志。
 *
 * @param runId              编排跑次 ID（挂载时生成，与工具 run_id 同源语义）
 * @param sessionId          会话 ID（可 null=无状态）
 * @param round              Planner 轮次（路由=1，逐次再规划 +1）
 * @param userText           用户本轮原始请求
 * @param history            主会话历史（仅路由/汇总进 Prompt；Executor 不携带）
 * @param originalPlanText   原始计划文本（再规划用户消息用）
 * @param observations       累计子任务观察（截断脱敏后）
 * @param forceFinishReason  强制收尾原因（null=正常收尾；非空时汇总消息追加触顶指令）
 */
public record PlannerContext(String runId,
                             String sessionId,
                             int round,
                             String userText,
                             List<Message> history,
                             String originalPlanText,
                             List<ObservationText> observations,
                             String forceFinishReason) {

    public PlannerContext {
        history = history == null ? List.of() : List.copyOf(history);
        observations = observations == null ? List.of() : List.copyOf(observations);
        originalPlanText = originalPlanText == null ? "" : originalPlanText;
    }

    /** 路由轮上下文（第 1 轮，无观察）。 */
    public static PlannerContext route(String runId, String sessionId,
                                       String userText, List<Message> history) {
        return new PlannerContext(runId, sessionId, 1, userText, history, "", List.of(), null);
    }

    /** 再规划轮上下文：携带原始计划文本与累计观察。 */
    public PlannerContext forReplan(int round, String originalPlanText,
                                    List<ObservationText> observations) {
        return new PlannerContext(runId, sessionId, round, userText, history,
                originalPlanText, observations, null);
    }

    /** 汇总轮上下文：可携带强制收尾原因（触顶时非 null）。 */
    public PlannerContext forSynth(int round, String originalPlanText,
                                   List<ObservationText> observations,
                                   String forceFinishReason) {
        return new PlannerContext(runId, sessionId, round, userText, history,
                originalPlanText, observations, forceFinishReason);
    }
}
