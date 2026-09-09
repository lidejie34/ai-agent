package com.dj.ai.agentchat.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.sse.ScheduledHeartbeat;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T9：SSE {@code event:tool} 帧（AC-64~69）。
 *
 * <p>MockMvc asyncDispatch 字节断言范式（复刻 FastJsonWebConfigTest/SseHeartbeatTest）：
 * ChatService stub 返回携带真实 {@link ToolCallBridge} 的 ChatStreamResult，
 * 测试线程经 bridge publish started/终态事件 → 响应体必须含
 * {@code event:tool\ndata:{...合法 JSON...}}、无反斜杠转义、帧序在 message/done 之前；
 * tool 帧成功发送后心跳计时 reset；done/error 终止后 bridge detach，迟到事件丢弃。
 */
@WebMvcTest(ChatController.class)
@Import(FastJsonWebConfig.class) // 与生产转换器链一致：tool 帧 JSON 走 fastjson2
class ChatControllerToolFrameTest {

    private static final String STREAM_URL = "/api/chat/stream";
    private static final String CALL_ID = "req-7|demo_builtin_tool|aaa12345";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private SseHeartbeatScheduler heartbeatScheduler;

    private ScheduledHeartbeat heartbeatHandle;

    @BeforeEach
    void setUp() {
        heartbeatHandle = mock(ScheduledHeartbeat.class);
        when(heartbeatScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenReturn(heartbeatHandle);
    }

    @Test
    void toolEvents_emittedAsToolFrames_orderedBeforeMessageAndDone() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallBridge bridge = new ToolCallBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), bridge));

        MvcResult mvcResult = startStream();

        // 工具线程语义：控制器挂 sink 后，经 bridge 推 started + succeeded
        bridge.publish(ToolEvent.started(CALL_ID, "demo_builtin_tool", "{\"minutes\":30}"));
        bridge.publish(ToolEvent.terminal(CALL_ID, "demo_builtin_tool", "{\"minutes\":30}",
                true, 42L, null));
        // 模型随后基于工具结果产出
        sink.tryEmitNext("日志分析完毕");
        sink.tryEmitComplete();

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:tool");
        assertThat(body).contains("\"callId\":\"" + CALL_ID + "\"");
        assertThat(body).contains("\"tool\":\"demo_builtin_tool\"");
        assertThat(body).contains("\"arguments\":\"{\\\"minutes\\\":30}\"");
        // data: 载荷为合法 JSON（每帧单行，无裸换行、无反斜杠 n 转义）
        assertThat(body).doesNotContain("\\n");

        List<JSONObject> toolFrames = toolFrames(body);
        assertThat(toolFrames).hasSize(2);
        assertThat(toolFrames.get(0).getString("status")).isEqualTo("started");
        assertThat(toolFrames.get(0).containsKey("durationMs")).isFalse();
        assertThat(toolFrames.get(1).getString("status")).isEqualTo("succeeded");
        assertThat(toolFrames.get(1).getLong("durationMs")).isEqualTo(42L);
        assertThat(toolFrames.get(1).containsKey("error")).isFalse();

        // 帧序：tool → message → done
        assertThat(body.indexOf("event:tool"))
                .isLessThan(body.indexOf("event:message"));
        assertThat(body.indexOf("event:message"))
                .isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("\"content\":\"日志分析完毕\"").contains("[DONE]");
    }

    @Test
    void toolFrameSent_resetsHeartbeat() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallBridge bridge = new ToolCallBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), bridge));

        MvcResult mvcResult = startStream();

        // 模型尚无输出：仅工具帧到达 → 心跳计时必须重置（工具执行也算活动，AC-67）
        bridge.publish(ToolEvent.started(CALL_ID, "demo_builtin_tool", "{}"));
        verify(heartbeatHandle).reset();

        sink.tryEmitComplete();
        dispatch(mvcResult);
    }

    @Test
    void failedToolEvent_frameCarriesErrorAndDuration() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallBridge bridge = new ToolCallBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), bridge));

        MvcResult mvcResult = startStream();

        bridge.publish(ToolEvent.started(CALL_ID, "demo_script_tool", "{}"));
        bridge.publish(ToolEvent.terminal(CALL_ID, "demo_script_tool", "{}",
                false, 3000L, "工具执行超时（3000ms）"));
        sink.tryEmitNext("工具超时了");
        sink.tryEmitComplete();

        String body = dispatch(mvcResult);

        List<JSONObject> toolFrames = toolFrames(body);
        assertThat(toolFrames).hasSize(2);
        JSONObject failed = toolFrames.get(1);
        assertThat(failed.getString("status")).isEqualTo("failed");
        assertThat(failed.getLong("durationMs")).isEqualTo(3000L);
        assertThat(failed.getString("error")).isEqualTo("工具执行超时（3000ms）");
        // 无堆栈泄漏
        assertThat(body).doesNotContain("Exception").doesNotContain("\tat ");
    }

    @Test
    void lateToolEvent_afterDone_detachedAndDiscarded() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallBridge bridge = new ToolCallBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), bridge));

        MvcResult mvcResult = startStream();

        sink.tryEmitNext("回答");
        sink.tryEmitComplete();
        // 流结束后工具线程才自然返回（abort/done 后迟到事件，AC-69）：必须被 detach 丢弃
        bridge.publish(ToolEvent.started("LATE-CALL-ID-MUST-NOT-APPEAR", "x", "{}"));

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("LATE-CALL-ID-MUST-NOT-APPEAR");
        assertThat(body).doesNotContain("event:tool");
    }

    @Test
    void fluxError_detachesBridge_lateEventDiscarded() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallBridge bridge = new ToolCallBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), bridge));

        MvcResult mvcResult = startStream();

        sink.tryEmitError(new ModelCallException("模型流式调用失败，请稍后重试。",
                new RuntimeException("internal-boom")));
        bridge.publish(ToolEvent.started("ERR-LATE-ID-MUST-NOT-APPEAR", "x", "{}"));

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:error").contains("MODEL_CALL_FAILED");
        assertThat(body).doesNotContain("ERR-LATE-ID-MUST-NOT-APPEAR");
        assertThat(body).doesNotContain("internal-boom");
    }

    @Test
    void noBridge_noToolFrames_byteCompatibleWithBaseline() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, Flux.just("你好")));

        MvcResult mvcResult = startStream();
        String body = dispatch(mvcResult);

        assertThat(body).doesNotContain("event:tool");
        assertThat(body).contains("event:message").contains("event:done");
    }

    // ---- helpers ----

    private MvcResult startStream() throws Exception {
        return mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"分析一下日志"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String dispatch(MvcResult mvcResult) throws Exception {
        return mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 从 SSE 响应体中解析全部 {@code event:tool} 帧的 data 载荷为 JSON（逐行扫描，兼容空行分隔）。 */
    private static List<JSONObject> toolFrames(String body) {
        List<JSONObject> frames = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if ("event:tool".equals(lines[i]) && lines[i + 1].startsWith("data:")) {
                frames.add(JSON.parseObject(lines[i + 1].substring("data:".length())));
            }
        }
        return frames;
    }
}
