package com.dj.ai.agentchat.dto;

import java.util.List;

/**
 * 对话请求体。
 *
 * @param message 本轮用户消息（必填，空白 400）
 * @param history 历史消息，按时间正序；服务端无会话状态，靠此字段实现多轮（可选）
 */
public record ChatRequest(String message, List<ChatMessage> history) {
}
