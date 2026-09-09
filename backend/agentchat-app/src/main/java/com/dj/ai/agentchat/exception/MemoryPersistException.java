package com.dj.ai.agentchat.exception;

/**
 * 同步链路模型调用成功后、会话消息成对落库失败时抛出（DataAccessException 体系）。
 * 同步接口映射 500 {@code MEMORY_PERSIST_FAILED}：reply 不返回，提示客户端整轮重试，
 * 避免「说了但没记住」。
 *
 * <p>流式链路落库失败<b>不</b>抛此异常：doOnComplete 内部吞掉仅 error 日志，done 照发（FR-12）。
 */
public class MemoryPersistException extends RuntimeException {

    public MemoryPersistException(String message) {
        super(message);
    }

    public MemoryPersistException(String message, Throwable cause) {
        super(message, cause);
    }
}
