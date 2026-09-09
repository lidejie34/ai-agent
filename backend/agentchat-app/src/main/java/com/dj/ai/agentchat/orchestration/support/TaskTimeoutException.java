package com.dj.ai.agentchat.orchestration.support;

/**
 * 编排模型调用超时（迭代5，T3）：{@link ModelInvoker} 在 Future.get 超时时抛出。
 * ExecutorClient 捕获后转为 {@code TaskOutcome(timedOut=true)}（AC-47）。
 */
public class TaskTimeoutException extends RuntimeException {

    public TaskTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
