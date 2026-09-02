package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ApiError;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.dto.StreamChunk;
import com.dj.ai.agentchat.exception.GlobalExceptionHandler;
import com.dj.ai.agentchat.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话接口（薄层：协议适配 + SSE 编排；消息组装与模型调用在 {@link ChatService}）。
 *
 * <p>SSE 事件协议：{@code event:message}（内容片段，可多帧）→ {@code event:done}（[DONE]，结束）；
 * 任何异常（缺 Key、参数错误、模型失败）都发 {@code event:error} 后立即 complete，保证不无限挂起。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final long sseTimeoutMs;

    public ChatController(ChatService chatService,
                          @Value("${app.chat.sse-timeout-ms:120000}") long sseTimeoutMs) {
        this.chatService = chatService;
        this.sseTimeoutMs = sseTimeoutMs;
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
            // 缺 Key / 参数错误等订阅前同步失败：转 error 事件后关闭，不挂起
            log.warn("SSE流式对话订阅前失败: {}", e.getMessage());
            sendErrorAndComplete(emitter, e);
            return emitter;
        }

        Disposable subscription = flux.subscribe(
                chunk -> {
                    int seq = chunkCount.incrementAndGet();
                    log.debug("SSE推送片段 #{}: {}", seq, chunk);
                    sendEvent(emitter, SseEmitter.event()
                            .name("message")
                            .data(new StreamChunk(chunk), MediaType.APPLICATION_JSON));
                },
                error -> {
                    log.warn("SSE流式对话异常: 已推送片段数={}, 耗时={}ms, 原因={}",
                            chunkCount.get(), System.currentTimeMillis() - start, error.getMessage());
                    sendErrorAndComplete(emitter, error);
                },
                () -> {
                    log.info("SSE流式对话完成: 推送片段数={}, 耗时={}ms",
                            chunkCount.get(), System.currentTimeMillis() - start);
                    sendEvent(emitter, SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                });

        // 超时 / 断连：取消订阅并释放资源
        emitter.onTimeout(() -> {
            log.warn("SSE流式对话超时: 已推送片段数={}, 耗时={}ms",
                    chunkCount.get(), System.currentTimeMillis() - start);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(t -> {
            log.debug("SSE连接异常（客户端可能已断开）: {}", t.getMessage());
            subscription.dispose();
        });
        emitter.onCompletion(subscription::dispose);

        return emitter;
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
