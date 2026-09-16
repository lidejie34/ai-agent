package com.dj.ai.agentchat.exception;

/**
 * SSE 流软截止超时（SSE 修复 D2）：由 Reactor 截止（{@code take(deadline)}）在容器硬超时
 * （sse-timeout-ms）前抛出，经 flux 错误消费器映射为 {@code event:error}（SSE_TIMEOUT）
 * 终态帧——前端把截断标为错误态、已生成正文保留，而非静默当作完成。
 */
public class SseStreamTimeoutException extends RuntimeException {

    public SseStreamTimeoutException(String message) {
        super(message);
    }
}
