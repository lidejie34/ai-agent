package com.dj.ai.agentchat.exception;

/**
 * 记忆阶段（模型调用前）不可用：建会话/加载历史/懒建表/获取 DB 连接失败（DataAccessException 体系，
 * 如 CannotGetJdbcConnectionException）时抛出。同步接口映射 503 {@code MEMORY_UNAVAILABLE}，
 * 流式接口在订阅前同步抛出 → error 事件（不发 event:session、不起心跳）。
 *
 * <p>无状态路径（sessionId 缺省/null）不触达 DB，不会抛此异常；DB 恢复后懒建表/连接自愈。
 * 原始异常作为 cause 保留用于日志，不向客户端外泄堆栈/SQL/连接信息。
 */
public class MemoryUnavailableException extends RuntimeException {

    public MemoryUnavailableException(String message) {
        super(message);
    }

    public MemoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
