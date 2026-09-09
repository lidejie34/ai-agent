package com.dj.ai.agentchat.util;

import com.dj.ai.agentchat.exception.InvalidChatRequestException;

import java.util.UUID;

/**
 * 会话 ID 校验/解析工具（迭代4）。消息路径（{@code ChatService}）与会话 REST 接口
 * （{@code SessionService}/{@code SessionController}）共用同一套 UUID 语义：
 * 非法格式直接 400，不触达 DB。
 */
public final class SessionIds {

    /** 非法 sessionId 的 400 提示（与 ChatService 历史消息文案一致）。 */
    public static final String ILLEGAL_SESSION_ID_MESSAGE =
            "sessionId 必须为服务端签发的 36 位会话 ID";

    /** 空白 sessionId 的 400 提示。 */
    public static final String EMPTY_SESSION_ID_MESSAGE = "sessionId 不能为空";

    private SessionIds() {
    }

    /**
     * 校验并原样返回合法 UUID；{@code null}/空白/非 36 位 UUID 均抛
     * {@link InvalidChatRequestException}（400）。
     */
    public static String requireUuid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new InvalidChatRequestException(EMPTY_SESSION_ID_MESSAGE);
        }
        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new InvalidChatRequestException(ILLEGAL_SESSION_ID_MESSAGE);
        }
        return sessionId;
    }
}
