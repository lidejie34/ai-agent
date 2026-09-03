package com.dj.ai.agentchat.dto;

/**
 * 同步对话响应体。
 *
 * @param reply     模型完整回复文本
 * @param model     本次实际使用的模型 ID（模型返回元数据优先，回退到配置的 ARK_CHAT_MODEL）
 * @param sessionId 会话 ID（迭代3）：新建态回显服务端生成的 UUID、续接态回显请求值；
 *                  无状态态为 {@code null}（fastjson2 默认省略 null 字段，报文不出现该键）
 */
public record ChatResponse(String reply, String model, String sessionId) {

    /**
     * 迭代1/2 兼容构造：无 sessionId（无状态响应）。
     */
    public ChatResponse(String reply, String model) {
        this(reply, model, null);
    }
}
