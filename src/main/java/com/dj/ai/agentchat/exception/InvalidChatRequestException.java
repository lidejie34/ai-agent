package com.dj.ai.agentchat.exception;

/**
 * 请求参数非法（message 空白、history role 非白名单、content 空白等）；映射 400。
 */
public class InvalidChatRequestException extends RuntimeException {

    public InvalidChatRequestException(String message) {
        super(message);
    }
}
