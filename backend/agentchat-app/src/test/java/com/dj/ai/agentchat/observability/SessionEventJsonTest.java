package com.dj.ai.agentchat.observability;

import com.alibaba.fastjson2.JSON;
import com.dj.ai.agentchat.dto.SessionEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代9（设计清单 #1 核销）：{@link SessionEvent} 可空第二字段经 fastjson2 的
 * null 省键行为——关闭态（traceId=null）帧字节与迭代8 逐字节一致；
 * 开启态双字段输出。
 */
class SessionEventJsonTest {

    @Test
    void nullTraceId_serializesExactlyLikeIteration8() {
        String json = JSON.toJSONString(new SessionEvent("sid-1", null));
        assertThat(json).isEqualTo("{\"sessionId\":\"sid-1\"}");
    }

    @Test
    void singleArgConstructor_alsoOmitsTraceId() {
        String json = JSON.toJSONString(new SessionEvent("sid-2"));
        assertThat(json).isEqualTo("{\"sessionId\":\"sid-2\"}");
    }

    @Test
    void nonNullTraceId_serializesBothFields() {
        String json = JSON.toJSONString(new SessionEvent("sid-3", "abcd1234abcd1234"));
        assertThat(json).isEqualTo("{\"sessionId\":\"sid-3\",\"traceId\":\"abcd1234abcd1234\"}");
    }
}
