package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ApiError;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.dto.SessionEvent;
import com.dj.ai.agentchat.dto.StreamChunk;
import com.dj.ai.agentchat.exception.GlobalExceptionHandler;
import com.dj.ai.agentchat.orchestration.OrchEventBridge;
import com.dj.ai.agentchat.orchestration.frame.PlanFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskFrame;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.sse.ScheduledHeartbeat;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import com.dj.ai.agentchat.tool.dto.ToolEventFrame;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
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

        ChatStreamResult result;
        try {
            // 记忆阶段（建会话/加载历史/DB 探测）在 service 同步段完成：失败在此被捕获，
            // 转 error 事件后关闭——不发 event:session、不启动心跳（AC-15）
            result = chatService.chatStream(request);
        } catch (RuntimeException e) {
            // 缺 Key / 参数错误 / 记忆不可用等订阅前同步失败：转 error 事件后关闭，不挂起
            log.warn("SSE流式对话订阅前失败: {}", e.getMessage());
            sendErrorAndComplete(emitter, e);
            return emitter;
        }

        // 记忆路径：记忆阶段已成功，在首个 message 帧（及心跳）之前回传会话 ID（FR-5/AC-4）。
        // 发送失败（客户端已断）→ completeWithError 已触发清理回调，直接返回
        if (result.sessionId() != null) {
            boolean sent = sendEvent(emitter, SseEmitter.event()
                    .name("session")
                    .data(new SessionEvent(result.sessionId()), MediaType.APPLICATION_JSON));
            if (!sent) {
                return emitter;
            }
        }

        // 订阅成功拿到 Flux 后再启动心跳：订阅前失败 / session 帧发送失败路径不会产生心跳任务
        ScheduledHeartbeat heartbeat = startHeartbeat(emitter);

        // 工具事件桥接（插入迭代 G）：工具线程 publish 的 started/终态事件在此转 event:tool 帧；
        // 帧到达同样重置心跳计时。五条终止路径全部 detach——abort/结束后迟到的工具事件丢弃（AC-69）
        ToolCallBridge toolBridge = result.toolBridge();
        if (toolBridge != null) {
            toolBridge.setSink(event -> {
                log.debug("SSE推送工具事件: tool={}, status={}", event.toolName(), event.status());
                boolean sent = sendEvent(emitter, SseEmitter.event()
                        .name("tool")
                        .data(ToolEventFrame.from(event), MediaType.APPLICATION_JSON));
                if (sent) {
                    resetHeartbeat(heartbeat);
                }
            });
        }

        // 编排过程帧桥接（迭代5）：编排线程 publish 的 plan/task 帧在此转 event:plan/event:task；
        // 帧到达同样重置心跳。普通路径 orchBridge=null（不产生新帧，AC-2）。
        // 五条终止路径与工具桥一同 detach——abort/结束后迟到的编排帧丢弃（AC-34）
        OrchEventBridge orchBridge = result.orchBridge();
        if (orchBridge != null) {
            orchBridge.setSink(frame -> {
                SseEmitter.SseEventBuilder event;
                if (frame instanceof PlanFrame planFrame) {
                    log.debug("SSE推送计划帧: round={}, tasks={}", planFrame.round(), planFrame.tasks().size());
                    event = SseEmitter.event().name("plan").data(planFrame, MediaType.APPLICATION_JSON);
                } else if (frame instanceof TaskFrame taskFrame) {
                    log.debug("SSE推送任务帧: task={}, status={}", taskFrame.taskId(), taskFrame.status());
                    event = SseEmitter.event().name("task").data(taskFrame, MediaType.APPLICATION_JSON);
                } else {
                    return;
                }
                if (sendEvent(emitter, event)) {
                    resetHeartbeat(heartbeat);
                }
            });
        }

        Flux<String> flux = result.chunks();
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
                    detachAll(toolBridge, orchBridge);
                    sendErrorAndComplete(emitter, error);
                    cancelHeartbeat(heartbeat);
                },
                () -> {
                    log.info("SSE流式对话完成: 推送片段数={}, 耗时={}ms",
                            chunkCount.get(), System.currentTimeMillis() - start);
                    detachAll(toolBridge, orchBridge);
                    sendEvent(emitter, SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    cancelHeartbeat(heartbeat);
                });

        // 超时 / 断连：取消订阅并停止心跳（五条退出路径全覆盖，均摘除工具桥 sink）
        emitter.onTimeout(() -> {
            log.warn("SSE流式对话超时: 已推送片段数={}, 耗时={}ms",
                    chunkCount.get(), System.currentTimeMillis() - start);
            detachAll(toolBridge, orchBridge);
            cancelHeartbeat(heartbeat);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(t -> {
            log.debug("SSE连接异常（客户端可能已断开）: {}", t.getMessage());
            detachAll(toolBridge, orchBridge);
            cancelHeartbeat(heartbeat);
            subscription.dispose();
        });
        emitter.onCompletion(() -> {
            detachAll(toolBridge, orchBridge);
            cancelHeartbeat(heartbeat);
            subscription.dispose();
        });

        return emitter;
    }

    /**
     * 五条终止路径（done/error/onTimeout/onCompletion/onError）统一摘除工具桥与编排桥
     * （迭代5）：终止后迟到的工具事件与 plan/task 帧均丢弃（AC-34/AC-69）。
     */
    private static void detachAll(@Nullable ToolCallBridge toolBridge,
                                  @Nullable OrchEventBridge orchBridge) {
        if (toolBridge != null) {
            toolBridge.detach();
        }
        if (orchBridge != null) {
            orchBridge.detach();
        }
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

    /**
     * 发送一帧；写入失败（客户端断连）时 completeWithError 触发 onError/onCompletion 清理，
     * 并返回 {@code false} 供调用方提前返回（不再起心跳/订阅）。
     */
    private boolean sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException e) {
            emitter.completeWithError(e);
            return false;
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
