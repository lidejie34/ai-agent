package com.dj.ai.agentchat.sse;

import java.time.Duration;

/**
 * SSE 心跳调度抽象（迭代 2）：把「定时发送心跳帧」与具体调度器解耦——
 * 生产实现为 daemon {@link java.util.concurrent.ScheduledExecutorService}（见
 * {@link DefaultSseHeartbeatScheduler}）；测试用手动触发的替身，零真实 sleep。
 */
public interface SseHeartbeatScheduler {

    /**
     * 按固定间隔循环调度任务（任务每次执行后重新计时，天然实现「发帧后重置间隔」）。
     *
     * @param task     到点执行的任务（发送心跳注释帧；实现需自行吞掉任务体异常，避免调度终止）
     * @param interval 心跳间隔
     * @return 可重置/取消的心跳句柄
     */
    ScheduledHeartbeat schedule(Runnable task, Duration interval);
}
