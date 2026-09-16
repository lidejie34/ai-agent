package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ApiError;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.StreamChunk;
import com.dj.ai.agentchat.observability.SlowRequestTracker;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.util.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 修复（D2）：Reactor 软截止——容器硬超时（sseTimeoutMs）前主动终止流，
 * 经 flux 错误消费器发 {@code event:error}（SSE_TIMEOUT）再 complete；
 * 前端把截断标为错误态（正文保留），不再被「流正常关闭但未见 done 帧」防御分支静默当作完成。
 *
 * <p>Spring 6.2 容器超时回调先置 complete 标志再执行 onTimeout 委托（回调内无法发帧），
 * 容器 onTimeout 仅作兜底清理——本类同时钉死该兜底语义。
 */
class ChatControllerTimeoutTest {

    private static final String TRACE_ID = "abcd1234abcd1234";

    /** 极小容器超时：软截止 deadline = max(120-5000, 60) = 60ms，测试零长等待 */
    private static final long TINY_TIMEOUT_MS = 120;

    private ChatService chatService;
    private SlowRequestTracker tracker;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        tracker = new SlowRequestTracker(1, 10);
        MDC.put(TraceIds.MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private ChatController controller() {
        return new ChatController(chatService, TINY_TIMEOUT_MS, false,
                Duration.ofSeconds(15), "keepalive", null, fixedProvider(tracker));
    }

    private static <T> ObjectProvider<T> fixedProvider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    @Test
    void softDeadline_sendsSseTimeoutErrorFrame_preservesChunks_recordsTimeout() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux()));

        SseEmitter emitter = controller().chatStream(new ChatRequest("你好", null));
        sink.tryEmitNext("已生成的前半段");
        // 上游不再产出：60ms 软截止触发 → 错误消费器发 error 帧 + complete（parallel 调度器异步）

        awaitTrue(() -> findApiError(emitter) != null);

        // 已推送片段不丢失
        assertThat(frameData(emitter).stream()
                .anyMatch(d -> d instanceof StreamChunk sc && "已生成的前半段".equals(sc.content())))
                .as("软截止前已推送的片段不丢失")
                .isTrue();
        // D2 核心：终态 error 帧 SSE_TIMEOUT
        ApiError error = findApiError(emitter);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("SSE_TIMEOUT");
        assertThat(error.message()).isEqualTo("模型响应超时，已生成内容可能不完整");
        assertThat(error.timestamp()).isNotBlank();
        // 终止语义：emitter complete；慢请求恰好 1 条 outcome=timeout
        awaitTrue(() -> tracker.size() == 1);
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(emitter, "complete")).get()).isTrue();
        SlowRequestTracker.SlowRequestEntry entry = tracker.snapshot().get(0);
        assertThat(entry.path()).isEqualTo("/api/chat/stream");
        assertThat(entry.outcome()).isEqualTo("timeout");
    }

    @Test
    void naturalCompletion_beforeDeadline_noErrorFrame_recordsDone() throws InterruptedException {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux()));

        SseEmitter emitter = controller().chatStream(new ChatRequest("你好", null));
        Thread.sleep(5); // 越过慢请求 1ms 阈值
        sink.tryEmitNext("完整回复");
        sink.tryEmitComplete();

        // 自然完成：done 帧路径（回归——软截止不误伤正常流）
        awaitTrue(() -> tracker.size() == 1);
        assertThat(findApiError(emitter)).isNull();
        assertThat(tracker.snapshot().get(0).outcome()).isEqualTo("done");
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(emitter, "complete")).get()).isTrue();
    }

    @Test
    void containerOnTimeout_fallbackCleanupOnly_noFrameSend() throws InterruptedException {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux()));

        SseEmitter emitter = controller().chatStream(new ChatRequest("你好", null));
        Thread.sleep(5); // 越过慢请求 1ms 阈值

        // 直接触发容器超时回调（SseHeartbeatTest 既有手法）：
        // DefaultCallback 先置 complete 再执行委托——委托内不得发帧，仅清理
        Runnable timeoutCallback = (Runnable) ReflectionTestUtils.getField(emitter, "timeoutCallback");
        assertThat(timeoutCallback).isNotNull();
        timeoutCallback.run();

        assertThat(findApiError(emitter)).isNull();
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.snapshot().get(0).outcome()).isEqualTo("timeout");
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(emitter, "complete")).get()).isTrue();
    }

    // ---- 帧检查辅助（bare emitter 无 handler，send 落入 earlySendAttempts，complete 不清空） ----

    private static ApiError findApiError(SseEmitter emitter) {
        return frameData(emitter).stream()
                .filter(d -> d instanceof ApiError)
                .map(d -> (ApiError) d)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> frameData(SseEmitter emitter) {
        Object attempts = ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(attempts).isInstanceOf(Set.class);
        return ((Set<ResponseBodyEmitter.DataWithMediaType>) attempts).stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .toList();
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("条件在 3s 内未满足");
    }
}
