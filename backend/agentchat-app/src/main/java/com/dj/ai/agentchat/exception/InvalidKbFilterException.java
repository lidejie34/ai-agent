package com.dj.ai.agentchat.exception;

/**
 * 知识库检索过滤参数非法（迭代10：kbProject/kbTags 超长/非法字符/数量越界）；映射 400
 * {@code KB_INVALID_FILTER}。与 {@link InvalidChatRequestException} 并列但错误码独立——
 * 前端/调用方可区分"消息体非法"与"知识库过滤维度非法"。
 */
public class InvalidKbFilterException extends RuntimeException {

    public InvalidKbFilterException(String message) {
        super(message);
    }
}
