package com.dj.ai.agentchat.config.web;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.dto.SessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1：{@link FastJsonWebConfig} 切片验证——@RequestBody 反序列化与 @ResponseBody 序列化
 * 真实走 fastjson2（实证 6.4：converter 插到列表最前后，MVC JSON 收发均由 fastjson2 处理）。
 */
@WebMvcTest(FastJsonWebConfigTest.EchoController.class)
@Import({FastJsonWebConfig.class, FastJsonWebConfigTest.EchoController.class})
class FastJsonWebConfigTest {

    private static final String UUID36 = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    @RestController
    static class EchoController {

        @PostMapping("/echo/stateless")
        ChatResponse stateless(@RequestBody ChatRequest request) {
            // 回显 message 以证明请求体经 fastjson2 反序列化成功；响应恒无 sessionId
            return new ChatResponse("echo:" + request.message(), "test-model");
        }

        @PostMapping("/echo/stateful")
        ChatResponse stateful(@RequestBody ChatRequest request) {
            // 原样回显 sessionId：null 应被省略、"" 应保留空串
            return new ChatResponse("r", "m", request.sessionId());
        }

        @PostMapping("/echo/event")
        SessionEvent event(@RequestBody ChatRequest request) {
            return new SessionEvent(request.sessionId());
        }
    }

    @Test
    void statelessResponse_serializedByFastjson_nullSessionIdOmitted() throws Exception {
        String body = mockMvc.perform(post("/echo/stateless")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("echo:hi"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("sessionId");
    }

    @Test
    void emptyStringSessionId_roundTripsAsEmptyString() throws Exception {
        mockMvc.perform(post("/echo/stateful")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"sessionId\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(""));
    }

    @Test
    void missingSessionId_roundTripsAsNull_andOmitted() throws Exception {
        String body = mockMvc.perform(post("/echo/stateful")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("sessionId");
    }

    @Test
    void uuidSessionId_roundTrips_andSessionEventSerialized() throws Exception {
        mockMvc.perform(post("/echo/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"sessionId\":\"" + UUID36 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(UUID36));
    }
}
