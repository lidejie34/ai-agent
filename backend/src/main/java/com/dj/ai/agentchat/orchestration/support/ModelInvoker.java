package com.dj.ai.agentchat.orchestration.support;

import com.dj.ai.agentchat.exception.ModelCallException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 阻塞式模型调用的统一超时包装（迭代5，T3，NFR-3）：Planner/Executor 的同步 {@code .call()}
 * 经 sdd-model-call daemon 池执行，编排线程以 {@code Future.get(超时)} 强时限等待——
 * 模型调用不占用 Netty event loop / SSE 心跳线程；超时 {@code cancel(true)} best-effort
 * 中断在途 HTTP 调用（最坏由 read-timeout 60s 自然终止，daemon cached 池空闲回收，无线程泄漏）。
 */
@Slf4j
public class ModelInvoker {

    private final ExecutorService pool;

    public ModelInvoker(ExecutorService pool) {
        this.pool = pool;
    }

    /**
     * 在模型调用池上执行并限时等待。
     *
     * @param timeoutNanos 超时（纳秒）；&lt;=0 表示不超时（继承模型 HTTP 读超时）
     * @throws TaskTimeoutException 超时（已 cancel）
     * @throws RuntimeException     业务异常原样抛出（ExecutionException 的 RuntimeException cause）；
     *                              中断 → {@link ModelCallException}
     */
    public <T> T call(Callable<T> callable, long timeoutNanos) {
        Future<T> future = pool.submit(callable);
        try {
            return timeoutNanos > 0 ? future.get(timeoutNanos, TimeUnit.NANOSECONDS) : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TaskTimeoutException("编排模型调用超时", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new ModelCallException("模型调用失败: " + (cause == null ? e.getMessage() : cause.getMessage()),
                    cause == null ? e : cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ModelCallException("模型调用被中断", e);
        }
    }
}
