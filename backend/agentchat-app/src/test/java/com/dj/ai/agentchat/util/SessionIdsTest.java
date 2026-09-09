package com.dj.ai.agentchat.util;

import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1：{@link SessionIds} 会话 ID 校验工具单测（消息接口与会话接口共用语义）。
 */
class SessionIdsTest {

    private static final String VALID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void validUuid_passesThrough() {
        assertThat(SessionIds.requireUuid(VALID)).isEqualTo(VALID);
        // 随机 UUID 亦合法
        String random = java.util.UUID.randomUUID().toString();
        assertThat(SessionIds.requireUuid(random)).isEqualTo(random);
    }

    @Test
    void null_throws400() {
        assertThatThrownBy(() -> SessionIds.requireUuid(null))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    void empty_throws400() {
        assertThatThrownBy(() -> SessionIds.requireUuid(""))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void blank_throws400() {
        assertThatThrownBy(() -> SessionIds.requireUuid("   "))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void nonUuid_throws400() {
        assertThatThrownBy(() -> SessionIds.requireUuid("abc"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessage(SessionIds.ILLEGAL_SESSION_ID_MESSAGE);
    }

    @Test
    void uuidWithSurroundingSpace_throws400() {
        // 带空格不合法（UUID.fromString 不容忍空白），400 不触达 DB
        assertThatThrownBy(() -> SessionIds.requireUuid(" " + VALID + " "))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void illegalUuidMessage_isStable() {
        assertThat(SessionIds.ILLEGAL_SESSION_ID_MESSAGE)
                .isEqualTo("sessionId 必须为服务端签发的 36 位会话 ID");
    }
}
