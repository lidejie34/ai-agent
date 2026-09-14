package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.observability.SlowRequestTracker;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.util.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代9 FR-4：{@link ChatController} 慢请求记录点——同步 done/error、SSE done 终态、
 * tracker 缺席（6 参旧构造）零行为、traceId 为空（关闭态 MDC 空）零行为。
 */
class ChatControllerSlowRequestTest {

    private static final String TRACE_ID = "abcd1234abcd1234";

    private ChatService chatService;
    private SlowRequestTracker tracker;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        // 阈值 1ms：测试中人为 sleep 确保超阈值
        tracker = new SlowRequestTracker(1, 10);
        MDC.put(TraceIds.MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private ChatController controllerWithTracker() {
        return new ChatController(chatService, 120000L, false,
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
    void syncDone_overThreshold_recordsDone() {
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(5);
            return new ChatResponse("回复", "m-x", null);
        });

        controllerWithTracker().chat(new ChatRequest("你好", List.of()));

        assertThat(tracker.size()).isEqualTo(1);
        SlowRequestTracker.SlowRequestEntry entry = tracker.snapshot().get(0);
        assertThat(entry.traceId()).isEqualTo(TRACE_ID);
        assertThat(entry.path()).isEqualTo("/api/chat");
        assertThat(entry.outcome()).isEqualTo("done");
        assertThat(entry.durationMs()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void syncError_recordsError_andRethrows() {
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(5);
            throw new RuntimeException("model down");
        });

        assertThatThrownBy(() -> controllerWithTracker().chat(new ChatRequest("你好", List.of())))
                .hasMessageContaining("model down");
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.snapshot().get(0).outcome()).isEqualTo("error");
    }

    @Test
    void sseComplete_recordsDoneOnce() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, sink.asFlux().delayElements(Duration.ofMillis(2))));

        SseEmitter emitter = controllerWithTracker().chatStream(new ChatRequest("你好", List.of()));
        sink.tryEmitNext("你");
        sink.tryEmitComplete();

        // 完成回调在 Reactor 线程异步执行，轮询等待记录落账
        awaitSize(1);
        assertThat(tracker.size()).isEqualTo(1);
        SlowRequestTracker.SlowRequestEntry entry = tracker.snapshot().get(0);
        assertThat(entry.path()).isEqualTo("/api/chat/stream");
        assertThat(entry.outcome()).isEqualTo("done");
        // AtomicBoolean 防重：emitter.complete() 触发的 onCompletion 不重复记录
        emitter.complete();
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void legacyConstructor_noTracker_recordsNothing() {
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(5);
            return new ChatResponse("回复", "m-x", null);
        });
        ChatController legacy = new ChatController(chatService, 120000L, false,
                Duration.ofSeconds(15), "keepalive", null);

        legacy.chat(new ChatRequest("你好", List.of()));

        assertThat(tracker.size()).isZero();
    }

    @Test
    void noMdcTraceId_recordsNothing_evenWithTracker() {
        MDC.clear();
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(5);
            return new ChatResponse("回复", "m-x", null);
        });

        controllerWithTracker().chat(new ChatRequest("你好", List.of()));

        assertThat(tracker.size()).isZero();
    }

    private void awaitSize(int expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (tracker.size() >= expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
