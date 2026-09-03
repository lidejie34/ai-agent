package com.dj.ai.agentchat.dto;

/**
 * SSE {@code event:session} 的 data 载荷（迭代3 FR-5）：记忆路径（新建/续接）在首个
 * {@code event:message} 帧之前发送，回传本轮会话 ID；无状态路径不发该事件。
 *
 * @param sessionId 服务端确认的会话 ID（新建态为服务端生成的 UUID，续接态为请求回显）
 */
public record SessionEvent(String sessionId) {
}
