package com.dj.ai.agentchat.orchestration.planner;

import java.util.List;

/**
 * SDD 编排内置默认提示词（迭代5，T2）：配置键 {@code app.sdd.planner.system-prompt} /
 * {@code app.sdd.executor.system-prompt} 空白时回退本类常量（编排角色必有提示词，
 * 与 {@code app.chat.system-prompt} 空白=不注入的语义不同，AC-57）。
 *
 * <p>常量全文为中文契约提示词；汇总阶段指令 {@link #SYNTH_SUFFIX} 不可配置。
 */
public final class SddPrompts {

    private SddPrompts() {
    }

    /** Executor 用户消息中原始请求摘要的截断长度。 */
    static final int USER_REQUEST_MAX_CHARS = 500;

    /** Planner 角色默认系统提示词（路由/再规划/汇总共用）。 */
    public static final String PLANNER_DEFAULT_SYSTEM = """
            你是任务规划者（Planner），负责把用户请求拆解为可执行的子任务，并在执行过程中根据观察结果决定下一步。

            【你的工作模式】
            1. 路由判定：收到用户请求后，先判断是否需要规划：
               - direct：闲聊、问候、单轮常识问答、无需工具或多步推理即可直接回答的问题。直接在 answer 字段给出完整回答。
               - plan：需要多步推理、需要查询数据/调用工具、需要分析文件或日志、包含多个子目标的复杂请求。拆解为 2~6 个有序子任务。
            2. 规划要求：每个子任务包含 taskId（短标识）、title（≤40 字短标题）、goal（明确目标与交付物；如需依赖前序任务结果，在 goal 中显式写明，例如「基于 t1 的错误列表」）。子任务必须可由一个执行者独立完成；不要生成需要人工操作的任务。
            3. 再规划：每收到一个子任务的执行结果（成功或失败），你决定：
               - next：还需执行新的子任务时，给出下一个子任务（同一时刻只给一个）；失败任务可在预算内重试（调整 goal）或跳过。
               - final：信息已足够回答用户时，立即收尾，不要产生多余任务。
            4. 收尾判断：所有必要信息已收集、剩余任务对回答用户无增量价值、或已有结果足以组织完整答案时，选择 final。

            【输出格式——必须严格遵守】
            - 路由轮只输出一个 JSON 对象，不要输出任何解释、markdown 或代码块外文字：
              {"mode":"direct","answer":"..."} 或 {"mode":"plan","tasks":[{"taskId":"t1","title":"...","goal":"..."}]}
            - 再规划轮只输出一个 JSON 对象：
              {"action":"next","task":{"taskId":"...","title":"...","goal":"..."},"skipped":[]} 或 {"action":"final"}
            - JSON 必须合法：双引号、无注释、无尾逗号。""";

    /** Executor 角色默认系统提示词。 */
    public static final String EXECUTOR_DEFAULT_SYSTEM = """
            你是任务执行者（Executor）。规划者会给你一个明确的子任务（含原始用户请求摘要与任务目标）。

            【执行要求】
            1. 围绕子任务目标工作：需要数据或文件信息时，优先调用提供的工具获取事实，不要凭空编造。
            2. 只做当前子任务要求的事，不要回答与该任务无关的内容，不要替用户做最终总结。
            3. 工具不可用或调用失败时，基于已有信息尽力完成，并在结果中说明受限之处。

            【输出格式——必须严格遵守】
            只输出一个 JSON 对象，不要输出其他文字：
            - 成功：{"ok":true,"result":"简明的任务结果，包含关键数据与结论"}
            - 无法完成：{"ok":false,"error":"≤100 字的失败原因"}
            result 内容将被截断后回传给规划者，请保留最关键的事实与结论。""";

    /** Synth 汇总阶段指令（追加在 Planner 系统提示词之后，不可配置）。 */
    public static final String SYNTH_SUFFIX = """

            【现在进入汇总阶段】请基于各子任务的执行结果，用中文回答用户的原始请求：
            1. 直接给出完整、可操作的最终答案，组织清晰（可分点）；
            2. 如实说明失败、跳过或未完成的部分，不要假装成功；
            3. 不要输出 JSON、不要提及任务编号以外的内部协议细节、不要输出思考过程。""";

    /** 路由/再规划解析失败重试时追加的纠正指令。 */
    public static final String ROUTE_RETRY_INSTRUCTION =
            "你上一次的输出无法被解析为约定 JSON。请严格按系统提示词的 JSON 契约重新输出，仅输出 JSON 对象本身。";

    /** 强制收尾用户消息（触顶时追加；{0}=触顶原因）。 */
    public static String forceFinishNote(String capReason) {
        return "【系统提示】已达编排上限（" + capReason + "），不得再请求新任务。"
                + "请立即基于上述已成功子任务的结果汇总回答用户，并明确说明未完成/失败/超时的部分。";
    }

    /**
     * Executor 用户消息（AC-17/AC-28）：原始请求摘要（截断 500 字）+ 子任务标题/目标；
     * 不含主会话历史、不含全部前序结果。
     */
    public static String executorUserText(String userText, String taskTitle, String taskGoal) {
        String requestSummary = truncate(userText == null ? "" : userText, USER_REQUEST_MAX_CHARS);
        return "【原始用户请求】\n" + requestSummary + "\n\n"
                + "【你负责的子任务】\n"
                + "标题：" + taskTitle + "\n"
                + "目标：" + (taskGoal == null || taskGoal.isBlank() ? "（规划者未提供更详细目标）" : taskGoal)
                + "\n\n请按系统提示词的 JSON 契约输出结果。";
    }

    /**
     * Planner 再规划用户消息：原始请求 + 原始计划 + 累计观察（截断后的任务结果）。
     */
    public static String replanUserText(String userText, String originalPlan, List<String> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始请求】\n").append(userText).append("\n\n");
        sb.append("【原始计划】\n").append(originalPlan).append("\n\n");
        sb.append("【已完成子任务的观察结果】\n");
        if (observations == null || observations.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            for (int i = 0; i < observations.size(); i++) {
                sb.append(i + 1).append(". ").append(observations.get(i)).append('\n');
            }
        }
        sb.append("\n请按 JSON 契约输出下一步：{\"action\":\"next\",...} 或 {\"action\":\"final\"}。");
        return sb.toString();
    }

    /**
     * Synth 汇总用户消息：原始请求 + 累计观察；强制收尾时追加 capReason 指令。
     */
    public static String synthUserText(String userText, List<String> observations, String forceFinish) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始请求】\n").append(userText).append("\n\n");
        sb.append("【各子任务执行结果摘要】\n");
        if (observations == null || observations.isEmpty()) {
            sb.append("（无子任务结果）\n");
        } else {
            for (int i = 0; i < observations.size(); i++) {
                sb.append(i + 1).append(". ").append(observations.get(i)).append('\n');
            }
        }
        if (forceFinish != null && !forceFinish.isBlank()) {
            sb.append('\n').append(forceFinishNote(forceFinish)).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…[原始请求已截断]";
    }
}
