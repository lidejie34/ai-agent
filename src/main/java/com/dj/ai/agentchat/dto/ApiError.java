package com.dj.ai.agentchat.dto;

/**
 * 统一错误结构（同步响应体 / SSE error 事件 data 共用）。
 *
 * @param code      错误码：BAD_REQUEST / ARK_NOT_CONFIGURED / MODEL_CALL_FAILED / INTERNAL_ERROR
 * @param message   人类可读提示，不含堆栈与密钥
 * @param timestamp 发生时间，ISO-8601 本地时间（yyyy-MM-dd'T'HH:mm:ss）
 */
public record ApiError(String code, String message, String timestamp) {
}
