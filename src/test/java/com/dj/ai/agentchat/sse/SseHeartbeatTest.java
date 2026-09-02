package com.dj.ai.agentchat.sse;

import com.dj.ai.agentchat.controller.ChatController;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.ChatNotConfiguredException;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T12/T13：SSE 心跳。
 *
 * <p>三部分：
 * <ol>
 *   <li>{@link SchedulerUnitTest}：生产调度器 {@link DefaultSseHeartbeatScheduler} 配假执行器
 *       （手动触发已调度任务，零真实 sleep），验证自调度/重置/取消幂等/异常自保；</li>
 *   <li>{@link ControllerSliceTest}：MockMvc 切片 + 手动调度器替身，触发心跳任务断言注释帧
 *       {@code :keepalive} 出现在响应体且不污染 message/done 序列，chunk 到达 reset、结束 cancel；</li>
 *   <li>{@link ControllerLifecycleTest}：直接构造控制器（无 Spring），经 ReflectionTestUtils
 *       触发 emitter 的 timeout/error/completion 三条回调，断言四路径全部 cancel（断连/超时
 *       在 MockMvc 中无法模拟容器回调），及开关关闭/订阅前失败均不调度。</li>
 * </ol>
 */
// ============ 1) 生产调度器单元测试 ============
class SchedulerUnitTest {

    private ScheduledExecutorService executor;
    private final List<Runnable> queuedOps = new ArrayList<>();
    private final List<Runnable> ticks = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = mock(ScheduledExecutorService.class);
        queuedOps.clear();
        ticks.clear();
        futures.clear();
        doAnswer(inv -> {
            queuedOps.add(inv.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(executor.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(inv -> {
            ticks.add(inv.getArgument(0));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
    }

    private void drainOps() {
        List<Runnable> copy = List.copyOf(queuedOps);
        queuedOps.clear();
        copy.forEach(Runnable::run);
    }

    private void runNextTick() {
        ticks.remove(0).run();
    }

    @Test
    void tick_firesTask_andSelfReschedules() {
        Runnable task = mock(Runnable.class);
        DefaultSseHeartbeatScheduler scheduler = new DefaultSseHeartbeatScheduler(executor);

        ScheduledHeartbeat heartbeat = scheduler.schedule(task, Duration.ofSeconds(15));
        drainOps(); // 启动 op → 首个 tick 入队
        assertThat(ticks).hasSize(1);
        verify(task, never()).run();

        runNextTick(); // 第 1 次到点：执行任务并自调度
        runNextTick(); // 第 2 次
        verify(task, org.mockito.Mockito.times(2)).run();
        assertThat(ticks).hasSize(1); // 每次执行后都重新排了一个 tick
        assertThat(heartbeat).isNotNull();
    }

    @Test
    void reset_cancelsCurrentFutureAndReschedules() {
        Runnable task = mock(Runnable.class);
        DefaultSseHeartbeatScheduler scheduler = new DefaultSseHeartbeatScheduler(executor);
        ScheduledHeartbeat heartbeat = scheduler.schedule(task, Duration.ofSeconds(15));
        drainOps();
        ScheduledFuture<?> firstFuture = futures.get(0);

        heartbeat.reset();
        drainOps();

        verify(firstFuture).cancel(false);
        assertThat(futures).hasSize(2); // 旧 tick 取消，新 tick 已排
    }

    @Test
    void cancel_stopsAllFutureTicks_andIsIdempotent() {
        Runnable task = mock(Runnable.class);
        DefaultSseHeartbeatScheduler scheduler = new DefaultSseHeartbeatScheduler(executor);
        ScheduledHeartbeat heartbeat = scheduler.schedule(task, Duration.ofSeconds(15));
        drainOps();

        heartbeat.cancel();
        heartbeat.cancel(); // 幂等
        drainOps();

        verify(futures.get(0)).cancel(false);
        // 已入队但尚未执行的 tick 在 cancel 后执行：任务体不再运行、不再自调度
        runNextTick();
        verify(task, never()).run();
        assertThat(ticks).isEmpty();
    }

    @Test
    void taskThrowing_tickStillReschedules_andSchedulerSurvives() {
        Runnable failingTask = mock(Runnable.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(failingTask).run();
        DefaultSseHeartbeatScheduler scheduler = new DefaultSseHeartbeatScheduler(executor);
        scheduler.schedule(failingTask, Duration.ofSeconds(15));
        drainOps();

        runNextTick();
        runNextTick(); // 任务持续失败，调度仍存活

        verify(failingTask, org.mockito.Mockito.times(2)).run();
        assertThat(ticks).hasSize(1);
    }
}

// ============ 2) 控制器切片：注释帧/重置/取消（T12 + T13 完成/出错路径） ============
@WebMvcTest(ChatController.class)
class ControllerSliceTest {

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
    void idleHeartbeatsEmitCommentFrames_thenChunkAndDone_unpolluted() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class))).thenReturn(sink.asFlux());

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"慢慢数到30"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatScheduler).schedule(taskCaptor.capture(), eq(Duration.ofSeconds(15)));
        Runnable heartbeatTask = taskCaptor.getValue();

        // 空闲期手动触发 3 次心跳（等价于 3 个 15s 间隔到点），零真实等待
        heartbeatTask.run();
        heartbeatTask.run();
        heartbeatTask.run();

        // 模型随后产出片段并结束
        sink.tryEmitNext("你好");
        sink.tryEmitComplete();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 3 个注释帧（:keepalive），且心跳帧全部出现在 message 事件之前，done 时序不变
        assertThat(countOccurrences(body, ":keepalive")).isEqualTo(3);
        assertThat(body.indexOf(":keepalive")).isLessThan(body.indexOf("event:message"));
        assertThat(body).contains("\"content\":\"你好\"");
        assertThat(body).contains("event:done").contains("[DONE]");
        // 注释帧不是 message 事件
        assertThat(countOccurrences(body, "event:message")).isEqualTo(1);

        // chunk 到达 → 重置计时；流结束 → 取消心跳（done 回调与 emitter.onCompletion 各取消一次，幂等）
        verify(heartbeatHandle).reset();
        verify(heartbeatHandle, org.mockito.Mockito.atLeastOnce()).cancel();
    }

    @Test
    void fluxError_sendsErrorEvent_andCancelsHeartbeat() throws Exception {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class))).thenReturn(sink.asFlux());

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        sink.tryEmitError(new ModelCallException("模型流式调用失败，请稍后重试。",
                new RuntimeException("internal-boom")));

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("event:error").contains("MODEL_CALL_FAILED");
        assertThat(body).doesNotContain("internal-boom");
        verify(heartbeatHandle, org.mockito.Mockito.atLeastOnce()).cancel();
        verify(heartbeatHandle, never()).reset();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int idx = haystack.indexOf(needle); idx >= 0; idx = haystack.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }
}

// ============ 3) 控制器生命周期：timeout/断连/completion 四路径 cancel + 开关/订阅前失败（T13） ============
class ControllerLifecycleTest {

    private ChatService chatService;
    private SseHeartbeatScheduler scheduler;
    private ScheduledHeartbeat handle;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        scheduler = mock(SseHeartbeatScheduler.class);
        handle = mock(ScheduledHeartbeat.class);
        when(scheduler.schedule(any(Runnable.class), any(Duration.class))).thenReturn(handle);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class))).thenReturn(sink.asFlux());
    }

    private ChatController controller(boolean heartbeatEnabled) {
        return new ChatController(chatService, 120000L, heartbeatEnabled,
                Duration.ofSeconds(15), "keepalive", scheduler);
    }

    private SseEmitter startStream() {
        return controller(true).chatStream(new ChatRequest("你好", null));
    }

    @SuppressWarnings("unchecked")
    private static Runnable callback(SseEmitter emitter, String fieldName) {
        Object callback = ReflectionTestUtils.getField(emitter, fieldName);
        assertThat(callback).isNotNull();
        return (Runnable) callback;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Throwable> errorCallback(SseEmitter emitter) {
        Object callback = ReflectionTestUtils.getField(emitter, "errorCallback");
        assertThat(callback).isNotNull();
        return (Consumer<Throwable>) callback;
    }

    @Test
    void heartbeatScheduled_withConfiguredInterval() {
        startStream();
        verify(scheduler).schedule(any(Runnable.class), eq(Duration.ofSeconds(15)));
    }

    @Test
    void onTimeout_cancelsHeartbeat() {
        SseEmitter emitter = startStream();
        callback(emitter, "timeoutCallback").run();
        verify(handle).cancel();
    }

    @Test
    void onClientDisconnect_cancelsHeartbeat() {
        SseEmitter emitter = startStream();
        errorCallback(emitter).accept(new IOException("Broken pipe"));
        verify(handle).cancel();
    }

    @Test
    void onCompletion_cancelsHeartbeat() {
        SseEmitter emitter = startStream();
        callback(emitter, "completionCallback").run();
        verify(handle).cancel();
    }

    @Test
    void heartbeatDisabled_neverSchedules() {
        ChatController disabled = new ChatController(chatService, 120000L, false,
                Duration.ofSeconds(15), "keepalive", scheduler);
        disabled.chatStream(new ChatRequest("你好", null));
        verifyNoInteractions(scheduler);
    }

    @Test
    void preSubscribeFailure_neverSchedulesHeartbeat() {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenThrow(new ChatNotConfiguredException("未检测到 ARK_API_KEY ..."));
        controller(true).chatStream(new ChatRequest("你好", null));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Duration.class));
    }

    @Test
    void nullScheduler_doesNotBreakStream() {
        ChatController noScheduler = new ChatController(chatService, 120000L, true,
                Duration.ofSeconds(15), "keepalive", null);
        SseEmitter emitter = noScheduler.chatStream(new ChatRequest("你好", null));
        assertThat(emitter).isNotNull();
    }

    @Test
    void fluxFromService_fluxApiUnchanged() {
        // 回归护栏：ChatService 类型仍可被控制器消费（Flux<String>），编译期契约
        when(chatService.chatStream(any(ChatRequest.class))).thenReturn(Flux.just("a", "b"));
        SseEmitter emitter = startStream();
        assertThat(emitter).isNotNull();
    }
}
