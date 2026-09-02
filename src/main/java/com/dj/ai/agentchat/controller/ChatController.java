package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ApiError;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.dto.StreamChunk;
import com.dj.ai.agentchat.exception.GlobalExceptionHandler;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.sse.ScheduledHeartbeat;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话接口（薄层：协议适配 + SSE 编排；消息组装与模型调用在 {@link ChatService}）。
 *
 * <p>SSE 事件协议：{@code event:message}（内容片段，可多帧）→ {@code event:done}（[DONE]，结束）；
 * 任何异常（缺 Key、参数错误、模型失败）都发 {@code event:error} 后立即 complete，保证不无限挂起。
 *
 * <p>迭代 2 新增：SSE 空闲心跳（注释帧 {@code :<text>}，标准 EventSource 忽略，不污染事件序列）。
 * 心跳经可注入的 {@link SseHeartbeatScheduler} 调度（生产 daemon 线程池、测试手动触发）；
 * 每个模型片段到达重置计时；完成/出错/超时/客户端断连四条退出路径全部取消心跳，不泄漏、不延后发帧。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final long sseTimeoutMs;
    private final boolean heartbeatEnabled;
    private final Duration heartbeatInterval;
    private final String heartbeatText;
    @Nullable
    private final SseHeartbeatScheduler heartbeatScheduler;

    public ChatController(ChatService chatService,
                          @Value("${app.chat.sse-timeout-ms:120000}") long sseTimeoutMs,
                          @Value("${app.chat.heartbeat.enabled:true}") boolean heartbeatEnabled,
                          @Value("${app.chat.heartbeat.interval:15s}") Duration heartbeatInterval,
                          @Value("${app.chat.heartbeat.text:keepalive}") String heartbeatText,
                          @Nullable SseHeartbeatScheduler heartbeatScheduler) {
        this.chatService = chatService;
        this.sseTimeoutMs = sseTimeoutMs;
        this.heartbeatEnabled = heartbeatEnabled;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatText = heartbeatText;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        int historySize = request.history() == null ? 0 : request.history().size();
        int messageLength = request.message() == null ? 0 : request.message().length();
        log.info("收到同步对话请求: message长度={}, history条数={}", messageLength, historySize);
        log.debug("同步对话请求内容: {}", request.message());

        ChatResponse response = chatService.chat(request);

        long elapsed = System.currentTimeMillis() - start;
        int replyLength = response.reply() == null ? 0 : response.reply().length();
        log.info("同步对话完成: model={}, 回复长度={}, 耗时={}ms", response.model(), replyLength, elapsed);
        log.debug("同步对话回复内容: {}", response.reply());
        return response;
    }

    @PostMapping(path = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        int historySize = request.history() == null ? 0 : request.history().size();
        int messageLength = request.message() == null ? 0 : request.message().length();
        log.info("收到SSE流式对话请求: message长度={}, history条数={}, 超时={}ms",
                messageLength, historySize, sseTimeoutMs);
        log.debug("SSE流式对话请求内容: {}", request.message());

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        AtomicInteger chunkCount = new AtomicInteger(0);

        Flux<String> flux;
        try {
            flux = chatService.chatStream(request);
        } catch (RuntimeException e) {
            // 缺 Key / 参数错误等订阅前同步失败：转 error 事件后关闭，不挂起；此路径不启动心跳
            log.warn("SSE流式对话订阅前失败: {}", e.getMessage());
            sendErrorAndComplete(emitter, e);
            return emitter;
        }

        // 订阅成功拿到 Flux 后再启动心跳：订阅前失败路径不会产生心跳任务
        ScheduledHeartbeat heartbeat = startHeartbeat(emitter);

        Disposable subscription = flux.subscribe(
                chunk -> {
                    int seq = chunkCount.incrementAndGet();
                    log.debug("SSE推送片段 #{}: {}", seq, chunk);
                    sendEvent(emitter, SseEmitter.event()
                            .name("message")
                            .data(new StreamChunk(chunk), MediaType.APPLICATION_JSON));
                    // 模型有输出：空闲计时归零
                    resetHeartbeat(heartbeat);
                },
                error -> {
                    log.warn("SSE流式对话异常: 已推送片段数={}, 耗时={}ms, 原因={}",
                            chunkCount.get(), System.currentTimeMillis() - start, error.getMessage());
                    sendErrorAndComplete(emitter, error);
                    cancelHeartbeat(heartbeat);
                },
                () -> {
                    log.info("SSE流式对话完成: 推送片段数={}, 耗时={}ms",
                            chunkCount.get(), System.currentTimeMillis() - start);
                    sendEvent(emitter, SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    cancelHeartbeat(heartbeat);
                });

        // 超时 / 断连：取消订阅并停止心跳（四条退出路径全覆盖）
        emitter.onTimeout(() -> {
            log.warn("SSE流式对话超时: 已推送片段数={}, 耗时={}ms",
                    chunkCount.get(), System.currentTimeMillis() - start);
            cancelHeartbeat(heartbeat);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(t -> {
            log.debug("SSE连接异常（客户端可能已断开）: {}", t.getMessage());
            cancelHeartbeat(heartbeat);
            subscription.dispose();
        });
        emitter.onCompletion(() -> {
            cancelHeartbeat(heartbeat);
            subscription.dispose();
        });

        return emitter;
    }

    // ---- 心跳 ----

    @Nullable
    private ScheduledHeartbeat startHeartbeat(SseEmitter emitter) {
        if (!heartbeatEnabled || heartbeatScheduler == null) {
            return null;
        }
        return heartbeatScheduler.schedule(() -> sendHeartbeatFrame(emitter), heartbeatInterval);
    }

    private void sendHeartbeatFrame(SseEmitter emitter) {
        try {
            // 注释帧：线上字节为 ":<text>\n"，EventSource/fetch SSE 解析均忽略，非 message/done/error 事件
            emitter.send(SseEmitter.event().comment(heartbeatText));
        } catch (IOException e) {
            log.debug("SSE 心跳帧写入失败（客户端可能已断开）: {}", e.getMessage());
            // 复用迭代 1 既有断连处理：触发 onError → dispose 订阅 + cancel 心跳
            emitter.completeWithError(e);
        }
    }

    private static void resetHeartbeat(@Nullable ScheduledHeartbeat heartbeat) {
        if (heartbeat != null) {
            heartbeat.reset();
        }
    }

    private static void cancelHeartbeat(@Nullable ScheduledHeartbeat heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel();
        }
    }

    private void sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException e) {
            // 客户端断连等写入失败：结束 emitter，触发 onError/onCompletion 取消订阅
            emitter.completeWithError(e);
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, Throwable error) {
        ApiError apiError = GlobalExceptionHandler.toApiError(error);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(apiError, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE error 事件写入失败（客户端可能已断开）: {}", e.getMessage());
        }
        emitter.complete();
    }
}
