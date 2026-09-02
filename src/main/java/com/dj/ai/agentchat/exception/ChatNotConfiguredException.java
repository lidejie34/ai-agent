package com.dj.ai.agentchat.exception;

/**
 * 未配置有效 ARK_API_KEY（缺失或为占位值）时抛出；同步接口映射 503，流式接口发 error 事件。
 */
public class ChatNotConfiguredException extends RuntimeException {

    public ChatNotConfiguredException(String message) {
        super(message);
    }
}
