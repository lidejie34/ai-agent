package com.dj.ai.agentchat.dto;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1：sessionId 三态反序列化与序列化契约（fastjson2 直接 API，不经 Spring）。
 *
 * <p>固化实证 6.2/6.3 结论：record 反序列化「字段缺省 / 显式 null → null」、「显式空串 → ""」、
 * UUID 原值保留；默认 writer 特性下 null 字段省略（无状态响应体不出现 sessionId 键）。
 * 次选构造器保证迭代1/2 的 {@code new ChatRequest/ChatResponse} 调用零改动。
 */
class ChatRequestSessionDtoTest {

    private static final String UUID36 = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void deserialize_missingSessionId_fieldIsNull() {
        ChatRequest req = JSON.parseObject("{\"message\":\"hi\"}", ChatRequest.class);
        assertThat(req.message()).isEqualTo("hi");
        assertThat(req.sessionId()).isNull();
    }

    @Test
    void deserialize_explicitNullSessionId_fieldIsNull() {
        ChatRequest req = JSON.parseObject("{\"message\":\"hi\",\"sessionId\":null}", ChatRequest.class);
        assertThat(req.sessionId()).isNull();
    }

    @Test
    void deserialize_emptyStringSessionId_fieldIsEmptyString() {
        ChatRequest req = JSON.parseObject("{\"message\":\"hi\",\"sessionId\":\"\"}", ChatRequest.class);
        assertThat(req.sessionId()).isNotNull().isEmpty();
    }

    @Test
    void deserialize_uuidSessionId_valuePreserved() {
        ChatRequest req = JSON.parseObject(
                "{\"message\":\"hi\",\"sessionId\":\"" + UUID36 + "\"}", ChatRequest.class);
        assertThat(req.sessionId()).isEqualTo(UUID36).hasSize(36);
    }

    @Test
    void deserialize_nestedHistory_recordsBound() {
        ChatRequest req = JSON.parseObject(
                "{\"message\":\"q\",\"history\":[{\"role\":\"user\",\"content\":\"你好\"},"
                        + "{\"role\":\"assistant\",\"content\":\"你好，有什么可以帮你？\"}]}",
                ChatRequest.class);
        assertThat(req.history()).hasSize(2);
        assertThat(req.history().get(0).role()).isEqualTo("user");
        assertThat(req.history().get(0).content()).isEqualTo("你好");
        assertThat(req.history().get(1).role()).isEqualTo("assistant");
    }

    @Test
    void serialize_statelessResponse_nullSessionIdOmitted() {
        String json = JSON.toJSONString(new ChatResponse("r", "m"));
        assertThat(json).contains("\"reply\":\"r\"").contains("\"model\":\"m\"");
        // 默认 writer 特性：null 字段省略，无状态报文与迭代1/2 线格式一致
        assertThat(json).doesNotContain("sessionId");
    }

    @Test
    void serialize_statefulResponse_sessionIdPresent() {
        String json = JSON.toJSONString(new ChatResponse("r", "m", UUID36));
        assertThat(json).contains("\"sessionId\":\"" + UUID36 + "\"");
    }

    @Test
    void serialize_sessionEvent_onlySessionIdField() {
        String json = JSON.toJSONString(new SessionEvent(UUID36));
        assertThat(json).isEqualTo("{\"sessionId\":\"" + UUID36 + "\"}");
    }

    @Test
    void secondaryConstructors_sessionIdDefaultsToNull() {
        ChatRequest request = new ChatRequest("hi", List.of());
        assertThat(request.sessionId()).isNull();
        assertThat(request.message()).isEqualTo("hi");

        ChatResponse response = new ChatResponse("r", "m");
        assertThat(response.sessionId()).isNull();
        assertThat(response.reply()).isEqualTo("r");
    }
}
