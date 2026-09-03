package com.dj.ai.agentchat.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1：{@link ChatMemoryProperties} 默认值契约（AC-18）：记忆默认开启、窗口默认 20、启动建表默认开启。
 */
class ChatMemoryPropertiesTest {

    @Test
    void defaults_enabledTrue_maxHistory20_initOnStartupTrue() {
        ChatMemoryProperties props = new ChatMemoryProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxHistory()).isEqualTo(20);
        assertThat(props.isInitOnStartup()).isTrue();
    }
}
