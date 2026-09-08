package com.dj.ai.agentchat.dto;

import java.util.List;

/**
 * 对话请求体。
 *
 * @param message   本轮用户消息（必填，空白 400）
 * @param history   历史消息，按时间正序；无状态模式靠此字段实现多轮（可选）。
 *                  记忆续接模式下该字段被忽略（以服务端存储为准，仅 warn 不报错）
 * @param sessionId 会话 ID（迭代3，可选，三态语义 FR-1）：
 *                  字段缺省或显式 {@code null} = 无状态（零 DB 交互）；
 *                  显式空串 {@code ""} = 首轮新建（服务端生成 UUID 并回传）；
 *                  36 位 UUID = 续接已有会话（非法格式 400）
 * @param sdd       SDD 子 Agent 编排开关（迭代5，可选，三态语义 AC-3/AC-5/AC-6）：
 *                  字段缺省或显式 {@code null} = 随服务端 {@code app.sdd.enabled} 总开关；
 *                  {@code true} = 请求编排（总开关关闭时 warn 忽略，不报错）；
 *                  {@code false} = 强制走迭代4 普通对话路径（总开关开启时也生效）
 */
public record ChatRequest(String message, List<ChatMessage> history, String sessionId, Boolean sdd) {

    /**
     * 迭代1/2/3/4 兼容构造：无 sdd 字段（null=随开关）。
     */
    public ChatRequest(String message, List<ChatMessage> history, String sessionId) {
        this(message, history, sessionId, null);
    }

    /**
     * 迭代1/2 兼容构造：无 sessionId（无状态）、无 sdd 字段。
     */
    public ChatRequest(String message, List<ChatMessage> history) {
        this(message, history, null, null);
    }
}
