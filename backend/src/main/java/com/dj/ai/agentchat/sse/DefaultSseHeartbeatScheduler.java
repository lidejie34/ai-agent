package com.dj.ai.agentchat.sse;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 生产心跳调度器：应用级单例，内部一个 daemon 线程的 {@link ScheduledExecutorService}。
 *
 * <p>所有状态变更（调度/重置/取消）都作为任务提交到该单线程执行器上串行执行，
 * 因此「到点发帧后自调度」与「chunk 到达 reset」之间无需加锁也无竞态。
 * 任务体异常自行捕获（仅 warn 日志），不中断后续心跳；写入失败（客户端断连）由任务体内
 * 调用 SseEmitter.completeWithError 走既有断连清理路径。
 *
 * <p>测试可经包内构造器注入手动触发的假执行器，零真实 sleep。
 */
@Slf4j
@Component
public class DefaultSseHeartbeatScheduler implements SseHeartbeatScheduler {

    private final ScheduledExecutorService executor;

    public DefaultSseHeartbeatScheduler() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** 测试专用：注入可控执行器（手动触发已调度任务，零等待）。 */
    DefaultSseHeartbeatScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public ScheduledHeartbeat schedule(Runnable task, Duration interval) {
        Heartbeat heartbeat = new Heartbeat(task, interval.toMillis());
        heartbeat.start();
        return heartbeat;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private final class Heartbeat implements ScheduledHeartbeat {

        private final Runnable task;
        private final long intervalMs;
        private ScheduledFuture<?> future;
        private boolean cancelled;

        Heartbeat(Runnable task, long intervalMs) {
            this.task = task;
            this.intervalMs = intervalMs;
        }

        void start() {
            // 首次调度也排队到执行器线程，保证全部状态变更同线程串行
            executor.execute(this::scheduleNext);
        }

        @Override
        public void reset() {
            executor.execute(() -> {
                if (cancelled) {
                    return;
                }
                if (future != null) {
                    future.cancel(false);
                }
                scheduleNext();
            });
        }

        @Override
        public void cancel() {
            executor.execute(() -> {
                cancelled = true;
                if (future != null) {
                    future.cancel(false);
                    future = null;
                }
            });
        }

        private void scheduleNext() {
            if (cancelled) {
                return;
            }
            future = executor.schedule(this::tick, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void tick() {
            if (cancelled) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                // 日志自保：心跳失败不影响流本身；断连由 task 内 completeWithError 处理
                log.warn("SSE 心跳帧发送失败: {}", t.getMessage());
            }
            // 自调度：发帧后重新计时（与 reset 同线程串行，无竞态）
            scheduleNext();
        }
    }
}
