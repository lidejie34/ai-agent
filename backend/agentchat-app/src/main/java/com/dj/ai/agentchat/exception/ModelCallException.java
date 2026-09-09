package com.dj.ai.agentchat.exception;

/**
 * 模型调用失败（网络、401/403、超时、上游 5xx、流式异常等）；同步接口映射 502，
 * 流式接口发 error 事件。原始异常作为 cause 保留用于日志，不向客户端外泄。
 */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
