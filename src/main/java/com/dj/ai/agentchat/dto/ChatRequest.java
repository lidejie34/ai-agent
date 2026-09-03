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
 */
public record ChatRequest(String message, List<ChatMessage> history, String sessionId) {

    /**
     * 迭代1/2 兼容构造：无 sessionId（无状态）。
     */
    public ChatRequest(String message, List<ChatMessage> history) {
        this(message, history, null);
    }
}
