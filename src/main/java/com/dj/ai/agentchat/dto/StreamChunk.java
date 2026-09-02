package com.dj.ai.agentchat.dto;

/**
 * SSE {@code event:message} 的 data 载荷：模型流式输出的一段文本。
 *
 * @param content 回复片段
 */
public record StreamChunk(String content) {
}
