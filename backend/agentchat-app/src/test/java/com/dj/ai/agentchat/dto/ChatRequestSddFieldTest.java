package com.dj.ai.agentchat.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：{@link ChatRequest} 新增可选 {@code sdd} 字段（AC-3/AC-5/AC-6）：
 * null=随开关；true=显式请求编排（开关关闭时 warn 忽略）；false=强制普通路径。
 * 既有 2/3 参构造保持兼容（委托传 null）。
 */
class ChatRequestSddFieldTest {

    @Test
    void fourArgConstructor_keepsSddTriState() {
        assertThat(new ChatRequest("你好", null, null, Boolean.TRUE).sdd()).isTrue();
        assertThat(new ChatRequest("你好", null, null, Boolean.FALSE).sdd()).isFalse();
        assertThat(new ChatRequest("你好", null, null, null).sdd()).isNull();
    }

    @Test
    void legacyConstructors_delegateNullSdd() {
        ChatRequest twoArg = new ChatRequest("你好", List.of());
        assertThat(twoArg.sdd()).isNull();
        assertThat(twoArg.message()).isEqualTo("你好");
        assertThat(twoArg.sessionId()).isNull();

        ChatRequest threeArg = new ChatRequest("你好", null, "sid-1");
        assertThat(threeArg.sdd()).isNull();
        assertThat(threeArg.sessionId()).isEqualTo("sid-1");
    }
}
