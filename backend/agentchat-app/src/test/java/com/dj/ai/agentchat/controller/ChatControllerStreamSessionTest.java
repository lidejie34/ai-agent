package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.sse.ScheduledHeartbeat;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T5：SSE event:session 时序（AC-4/5/7/15 流式帧部分）。
 * MockMvc 异步派发捕获 SSE 文本；帧 JSON 真实走 fastjson2（@Import FastJsonWebConfig）。
 */
@WebMvcTest(ChatController.class)
@Import(FastJsonWebConfig.class)
class ChatControllerStreamSessionTest {

    private static final String STREAM_URL = "/api/chat/stream";
    private static final String UUID36 = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private SseHeartbeatScheduler heartbeatScheduler;

    private String dispatch(String requestBody) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void newSession_sessionFrameBeforeMessages_thenDone() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(UUID36, Flux.just("你", "好")));

        String body = dispatch("{\"message\":\"你好\",\"sessionId\":\"\"}");

        // session 帧是第一个事件，data 为 fastjson2 序列化的 {"sessionId":"..."}
        int sessionIdx = body.indexOf("event:session");
        int firstMessageIdx = body.indexOf("event:message");
        int doneIdx = body.indexOf("event:done");
        assertThat(sessionIdx).isGreaterThanOrEqualTo(0);
        assertThat(sessionIdx).isLessThan(firstMessageIdx);
        assertThat(firstMessageIdx).isLessThan(doneIdx);
        assertThat(body).contains("{\"sessionId\":\"" + UUID36 + "\"}");
        assertThat(body).contains("{\"content\":\"你\"}").contains("{\"content\":\"好\"}");
        assertThat(body).contains("[DONE]");
    }

    @Test
    void resume_sessionFrameEchoesRequestSessionId() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(UUID36, Flux.just("嗯")));

        String body = dispatch("{\"message\":\"追问\",\"sessionId\":\"" + UUID36 + "\"}");

        assertThat(body.indexOf("event:session")).isLessThan(body.indexOf("event:message"));
        assertThat(body).contains("{\"sessionId\":\"" + UUID36 + "\"}");
    }

    @Test
    void stateless_noSessionFrame() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(ChatStreamResult.stateless(Flux.just("a")));

        String body = dispatch("{\"message\":\"你好\"}");

        assertThat(body).doesNotContain("event:session").doesNotContain("sessionId");
        assertThat(body).contains("event:message").contains("event:done");
    }

    @Test
    void memoryPhaseFailure_errorFrameOnly_noSessionFrame_noHeartbeat() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenThrow(new MemoryUnavailableException("会话服务暂不可用，请稍后重试"));
        ScheduledHeartbeat handle = mock(ScheduledHeartbeat.class);
        when(heartbeatScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenReturn(handle);

        String body = dispatch("{\"message\":\"你好\",\"sessionId\":\"\"}");

        // 订阅前失败：仅 error 帧（MEMORY_UNAVAILABLE），不发 session、不起心跳
        assertThat(body).contains("event:error").contains("MEMORY_UNAVAILABLE");
        assertThat(body).doesNotContain("event:session").doesNotContain("event:message");
        verify(heartbeatScheduler, never()).schedule(any(Runnable.class), any(Duration.class));
    }

    @Test
    void modelErrorAfterSession_sessionFrameStillSent() throws Exception {
        // AC-5：记忆阶段成功后模型流随即 onError——session 帧先于 error 帧
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(UUID36,
                        Flux.error(new com.dj.ai.agentchat.exception.ModelCallException(
                                "模型流式调用失败，请稍后重试。", new RuntimeException("boom")))));

        String body = dispatch("{\"message\":\"你好\",\"sessionId\":\"\"}");

        assertThat(body.indexOf("event:session")).isGreaterThanOrEqualTo(0);
        assertThat(body.indexOf("event:session")).isLessThan(body.indexOf("event:error"));
        assertThat(body).contains("MODEL_CALL_FAILED");
    }

    @Test
    void sessionSendOrder_withHeartbeatComment_sessionFrameStillFirst() throws Exception {
        // 心跳注释帧可能早于模型首片段，但必须晚于 session 帧
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(UUID36, sink.asFlux()));
        ScheduledHeartbeat handle = mock(ScheduledHeartbeat.class);
        when(heartbeatScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenReturn(handle);

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"慢慢说\",\"sessionId\":\"\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 订阅成功后心跳任务已调度（session 帧在其之前已发）；手动触发一次心跳注释帧
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatScheduler).schedule(taskCaptor.capture(), any(Duration.class));
        taskCaptor.getValue().run();

        sink.tryEmitNext("片段");
        sink.tryEmitComplete();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body.indexOf("event:session")).isLessThan(body.indexOf(":keepalive"));
        assertThat(body.indexOf(":keepalive")).isLessThan(body.indexOf("event:message"));
    }
}
