package com.dj.ai.agentchat.config.web;

import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.dto.SessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

        /** 迷你 SSE 端点：一帧 JSON 对象 + 一帧纯文本 [DONE]，用于锁线网分帧格式。 */
        @GetMapping("/echo/sse")
        SseEmitter sse() {
            SseEmitter emitter = new SseEmitter(5000L);
            Thread sender = new Thread(() -> {
                try {
                    emitter.send(SseEmitter.event().name("session").data(new SessionEvent(UUID36)));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            sender.setDaemon(true);
            sender.start();
            return emitter;
        }
    }

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void stringConverter_precedesFastjson_soSseTextFramesAreNotJsonQuoted() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();
        int stringIdx = -1;
        int fastjsonIdx = -1;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof StringHttpMessageConverter && stringIdx < 0) {
                stringIdx = i;
            }
            if (converters.get(i) instanceof FastJsonHttpMessageConverter && fastjsonIdx < 0) {
                fastjsonIdx = i;
            }
        }
        assertThat(stringIdx).as("StringHttpMessageConverter 必须存在且排在 fastjson2 之前").isGreaterThanOrEqualTo(0);
        assertThat(fastjsonIdx).as("FastJsonHttpMessageConverter 必须存在").isGreaterThanOrEqualTo(0);
        assertThat(stringIdx).isLessThan(fastjsonIdx);

        // 文本帧（event:/data 前缀、[DONE]、:keepalive）由 String 转换器原样输出
        assertThat(converters.get(stringIdx).canWrite(String.class, MediaType.TEXT_PLAIN)).isTrue();
        // DTO 对象 String 转换器不命中，回落 fastjson2 输出 JSON
        assertThat(converters.get(stringIdx).canWrite(SessionEvent.class, MediaType.ALL)).isFalse();
        assertThat(converters.get(fastjsonIdx).canWrite(SessionEvent.class, MediaType.ALL)).isTrue();
    }

    @Test
    void sseWireFormat_framesUseRawNewlines_andDoneIsNotJsonQuoted() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/echo/sse"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult(5000);

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 正确线格式：event:/data: 行 + 真实换行
        assertThat(body).contains("event:session\n").contains("data:{\"sessionId\":\"" + UUID36 + "\"}");
        assertThat(body).contains("event:done\n").contains("data:[DONE]");
        // 回归红线（迭代4 冒烟发现的 fastjson2 转义 bug）：帧不得被 JSON 字符串包裹
        assertThat(body).doesNotContain("\"event:").doesNotContain("\\n");
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
