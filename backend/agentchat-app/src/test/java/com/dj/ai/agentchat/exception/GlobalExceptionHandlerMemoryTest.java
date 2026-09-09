package com.dj.ai.agentchat.exception;

import com.dj.ai.agentchat.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2：记忆相关异常的错误码映射（AC-15/AC-17 错误码部分、NFR-5）。
 *
 * <p>记忆阶段（模型调用前）DB/建表/连接失败 → {@link MemoryUnavailableException}：
 * 同步 503 {@code MEMORY_UNAVAILABLE}、SSE error 同码；模型成功后落库失败 →
 * {@link MemoryPersistException}：同步 500 {@code MEMORY_PERSIST_FAILED}
 * （流式落库失败仅日志，不进 SSE error 映射）。
 */
class GlobalExceptionHandlerMemoryTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void memoryUnavailable_mapsTo503_withMemoryUnavailableCode() {
        ResponseEntity<ApiError> response =
                handler.handleMemoryUnavailable(new MemoryUnavailableException("会话服务暂不可用，请稍后重试"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MEMORY_UNAVAILABLE");
        assertThat(response.getBody().message()).isEqualTo("会话服务暂不可用，请稍后重试");
        assertThat(response.getBody().timestamp()).isNotBlank();
    }

    @Test
    void memoryPersistFailed_mapsTo500_withMemoryPersistFailedCode() {
        ResponseEntity<ApiError> response =
                handler.handleMemoryPersistFailed(new MemoryPersistException("会话保存失败，请重试本轮对话"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MEMORY_PERSIST_FAILED");
        assertThat(response.getBody().message()).isEqualTo("会话保存失败，请重试本轮对话");
    }

    @Test
    void toApiError_memoryUnavailable_codeAndMessagePreserved() {
        ApiError apiError = GlobalExceptionHandler.toApiError(
                new MemoryUnavailableException("会话服务暂不可用，请稍后重试"));

        assertThat(apiError.code()).isEqualTo("MEMORY_UNAVAILABLE");
        assertThat(apiError.message()).isEqualTo("会话服务暂不可用，请稍后重试");
        assertThat(apiError.timestamp()).isNotBlank();
    }

    @Test
    void toApiError_existingMappingsNotRegressed() {
        // 迭代1/2 既有映射回归护栏
        assertThat(GlobalExceptionHandler.toApiError(
                new ModelCallException("模型流式调用失败，请稍后重试。", new RuntimeException()))
                .code()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(GlobalExceptionHandler.toApiError(
                new InvalidChatRequestException("message 不能为空"))
                .code()).isEqualTo("BAD_REQUEST");
        assertThat(GlobalExceptionHandler.toApiError(
                new ChatNotConfiguredException("未检测到 ARK_API_KEY ..."))
                .code()).isEqualTo("ARK_NOT_CONFIGURED");
    }

    @Test
    void errorMessages_doNotLeakStackOrSql() {
        // 异常 message 人类可读；cause 不外泄到 ApiError
        MemoryUnavailableException withCause = new MemoryUnavailableException(
                "会话服务暂不可用，请稍后重试",
                new RuntimeException("java.net.ConnectException: Connection refused: connect to 127.0.0.1:13306"));

        ApiError apiError = GlobalExceptionHandler.toApiError(withCause);
        assertThat(apiError.message()).doesNotContain("Connection refused").doesNotContain("13306");
    }
}
