package com.dj.ai.agentchat.exception;

/**
 * 工具服务不可用（插入迭代 G）：管理端 DB 访问失败（Mapper 抛 DataAccessException）或
 * 写后刷新失败时抛出，映射 503 {@code TOOLS_UNAVAILABLE}。
 *
 * <p>对话链路不使用本异常——工具装载失败仅降级为空工具集（WARN，不 5xx）；
 * 仅管理端（要求强一致）转为 503 提示重试。原始异常作为 cause 保留用于日志，
 * 不向客户端外泄堆栈/SQL/连接信息。
 */
public class ToolsUnavailableException extends RuntimeException {

    public ToolsUnavailableException(String message) {
        super(message);
    }

    public ToolsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
