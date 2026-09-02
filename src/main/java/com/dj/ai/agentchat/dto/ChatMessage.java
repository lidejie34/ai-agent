package com.dj.ai.agentchat.dto;

/**
 * 历史消息。
 *
 * @param role    角色，仅接受 {@code user} / {@code assistant}（其他值 400）
 * @param content 消息文本（空白 400）
 */
public record ChatMessage(String role, String content) {
}
