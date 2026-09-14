package com.dj.ai.agentchat.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;

/**
 * 工具证据消息载体（迭代8）：仅供落库（{@code role='tool_evidence'}），不参与模型请求构造。
 *
 * <p>{@link #getMessageType()} 返回 {@link MessageType#ASSISTANT} 占位——
 * {@code MybatisChatMemory.add()} 以 {@code instanceof} 优先识别本类型写入
 * {@code tool_evidence}，占位 role 不会真正落库；其余 Message 契约方法最小实现。
 */
public class ToolEvidenceMessage implements Message {

    private final String text;

    public ToolEvidenceMessage(String text) {
        this.text = text;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of();
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.ASSISTANT;
    }
}
