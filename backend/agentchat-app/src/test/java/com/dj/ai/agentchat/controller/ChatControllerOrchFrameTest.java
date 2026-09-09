package com.dj.ai.agentchat.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.orchestration.OrchEventBridge;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.sse.ScheduledHeartbeat;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import com.dj.ai.agentchat.orchestration.frame.PlanFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskView;
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
 * T5：SSE {@code event:plan} / {@code event:task} 编排帧（AC-31~34/AC-35）。
 *
 * <p>复刻 ChatControllerToolFrameTest 的 MockMvc asyncDispatch 字节断言范式：
 * ChatService stub 返回携带真实 {@link OrchEventBridge} 的 ChatStreamResult，
 * 编排线程经 bridge publish plan/task 帧 → 响应体必须含对应 event 行与合法 JSON、
 * 帧到达重置心跳；done/error 五路径 detach 后迟到帧丢弃；无 orchBridge 时字节兼容迭代4。
 */
@WebMvcTest(ChatController.class)
@Import(FastJsonWebConfig.class)
class ChatControllerOrchFrameTest {

    private static final String STREAM_URL = "/api/chat/stream";

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
    void planAndTaskEvents_emittedAsFrames_orderedBeforeMessageAndDone() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        OrchEventBridge bridge = new OrchEventBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), null, bridge));

        MvcResult mvcResult = startStream();

        bridge.publish(new PlanFrame(1,
                List.of(new TaskView("t1", "扫描错误日志", TaskView.PENDING),
                        new TaskView("t2", "汇总结论", TaskView.PENDING)),
                Boolean.TRUE));
        bridge.publish(TaskFrame.started("t1", "扫描错误日志", 1));
        bridge.publish(TaskFrame.succeeded("t1", "扫描错误日志", 8231L));
        sink.tryEmitNext("分析完成");
        sink.tryEmitComplete();

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:plan").contains("event:task");
        List<JSONObject> planFrames = framesOf(body, "plan");
        assertThat(planFrames).hasSize(1);
        JSONObject plan = planFrames.get(0);
        assertThat(plan.getIntValue("round")).isEqualTo(1);
        assertThat(plan.getBooleanValue("truncated")).isTrue();
        assertThat(plan.getJSONArray("tasks")).hasSize(2);
        assertThat(plan.getJSONArray("tasks").getJSONObject(0).getString("status"))
                .isEqualTo("pending");

        List<JSONObject> taskFrames = framesOf(body, "task");
        assertThat(taskFrames).hasSize(2);
        assertThat(taskFrames.get(0).getString("status")).isEqualTo("started");
        assertThat(taskFrames.get(0).getIntValue("round")).isEqualTo(1);
        assertThat(taskFrames.get(0).containsKey("durationMs")).isFalse();
        assertThat(taskFrames.get(1).getString("status")).isEqualTo("succeeded");
        assertThat(taskFrames.get(1).getLong("durationMs")).isEqualTo(8231L);
        assertThat(taskFrames.get(1).containsKey("round")).isFalse();
        // 帧序：plan → task → message → done
        assertThat(body.indexOf("event:plan")).isLessThan(body.indexOf("event:task"));
        assertThat(body.indexOf("event:task")).isLessThan(body.indexOf("event:message"));
        assertThat(body.indexOf("event:message")).isLessThan(body.indexOf("event:done"));
        // 载荷单行合法 JSON，无裸换行
        assertThat(body).doesNotContain("\\n");
    }

    @Test
    void orchFrameSent_resetsHeartbeat() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        OrchEventBridge bridge = new OrchEventBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), null, bridge));

        MvcResult mvcResult = startStream();

        // 模型尚无输出：仅 plan 帧到达 → 心跳计时必须重置（编排活动也算活动，AC-33）
        bridge.publish(new PlanFrame(1,
                List.of(new TaskView("t1", "任务", TaskView.PENDING)), null));
        verify(heartbeatHandle).reset();

        sink.tryEmitComplete();
        dispatch(mvcResult);
    }

    @Test
    void lateOrchFrame_afterDone_detachedAndDiscarded() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        OrchEventBridge bridge = new OrchEventBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), null, bridge));

        MvcResult mvcResult = startStream();

        sink.tryEmitNext("回答");
        sink.tryEmitComplete();
        // 流结束后迟到的编排帧（五终止路径 detach，AC-34）：必须丢弃
        bridge.publish(TaskFrame.started("LATE-TASK-MUST-NOT-APPEAR", "迟到任务", 2));

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("LATE-TASK-MUST-NOT-APPEAR");
        assertThat(body).doesNotContain("event:task");
    }

    @Test
    void fluxError_detachesOrchBridge_lateFrameDiscarded() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        OrchEventBridge bridge = new OrchEventBridge();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux(), null, bridge));

        MvcResult mvcResult = startStream();

        sink.tryEmitError(new ModelCallException("模型流式调用失败，请稍后重试。",
                new RuntimeException("internal-boom")));
        bridge.publish(new PlanFrame(9,
                List.of(new TaskView("ERR-LATE-MUST-NOT-APPEAR", "迟到", TaskView.PENDING)), null));

        String body = dispatch(mvcResult);

        assertThat(body).contains("event:error").contains("MODEL_CALL_FAILED");
        assertThat(body).doesNotContain("ERR-LATE-MUST-NOT-APPEAR");
        assertThat(body).doesNotContain("internal-boom");
    }

    @Test
    void noOrchBridge_byteCompatibleWithBaseline() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, Flux.just("你好")));

        MvcResult mvcResult = startStream();
        String body = dispatch(mvcResult);

        assertThat(body).doesNotContain("event:plan").doesNotContain("event:task");
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

    private static List<JSONObject> framesOf(String body, String eventName) {
        List<JSONObject> frames = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (("event:" + eventName).equals(lines[i]) && lines[i + 1].startsWith("data:")) {
                frames.add(JSON.parseObject(lines[i + 1].substring("data:".length())));
            }
        }
        return frames;
    }
}
