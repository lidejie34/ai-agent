package com.dj.ai.agentchat.exception;

import com.dj.ai.agentchat.dto.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局异常归一：同步接口产出统一 {@link ApiError} JSON；
 * {@link #toApiError(Throwable)} 同时供 SSE error 事件复用错误码映射。
 * 任何情况下都不向客户端外泄堆栈或密钥。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @ExceptionHandler(InvalidChatRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidChatRequestException e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableJson(HttpMessageNotReadableException e) {
        log.debug("请求体反序列化失败: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体不是合法的 JSON 或字段类型错误");
    }

    @ExceptionHandler(ChatNotConfiguredException.class)
    public ResponseEntity<ApiError> handleNotConfigured(ChatNotConfiguredException e) {
        log.warn("模型未配置: {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "ARK_NOT_CONFIGURED", e.getMessage());
    }

    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ApiError> handleModelCallFailed(ModelCallException e) {
        log.warn("模型调用失败: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误，请稍后重试");
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, now()));
    }

    /**
     * SSE error 事件的错误映射：与同步接口的状态码/错误码语义保持一致。
     */
    public static ApiError toApiError(Throwable e) {
        if (e instanceof ChatNotConfiguredException) {
            return new ApiError("ARK_NOT_CONFIGURED", e.getMessage(), now());
        }
        if (e instanceof InvalidChatRequestException) {
            return new ApiError("BAD_REQUEST", e.getMessage(), now());
        }
        if (e instanceof ModelCallException) {
            return new ApiError("MODEL_CALL_FAILED", e.getMessage(), now());
        }
        return new ApiError("MODEL_CALL_FAILED", "模型流式调用失败，请稍后重试。", now());
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).format(TS);
    }
}
