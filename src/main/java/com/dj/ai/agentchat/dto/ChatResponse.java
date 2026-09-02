package com.dj.ai.agentchat.dto;

/**
 * 同步对话响应体。
 *
 * @param reply 模型完整回复文本
 * @param model 本次实际使用的模型 ID（模型返回元数据优先，回退到配置的 ARK_CHAT_MODEL）
 */
public record ChatResponse(String reply, String model) {
}
