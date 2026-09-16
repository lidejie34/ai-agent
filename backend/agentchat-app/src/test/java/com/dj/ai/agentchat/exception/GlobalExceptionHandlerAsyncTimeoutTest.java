package com.dj.ai.agentchat.exception;

import com.dj.ai.agentchat.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 修复（D2/D3）：异步超时异常归一 + SSE_TIMEOUT 终态帧工厂。
 *
 * <p>D3：SseEmitter 超时后容器把 {@link AsyncRequestTimeoutException} 重新分发进异常处理链，
 * 归一为「debug 日志 + 不写响应体」（返回 null），不再命中兜底 Exception 处理器——
 * 不再 ERROR「未处理异常」，也不再把 INTERNAL_ERROR JSON 追加进已完成的 SSE 字节流。
 *
 * <p>D2：{@link SseStreamTimeoutException}（Reactor 软截止在容器硬超时前抛出）经
 * {@link GlobalExceptionHandler#toApiError(Throwable)} 映射为 SSE_TIMEOUT error 帧。
 */
class GlobalExceptionHandlerAsyncTimeoutTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void asyncRequestTimeout_returnsNull_writesNothing() {
        ResponseEntity<Void> response =
                handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException());

        assertThat(response).isNull();
    }

    @Test
    void handleUnexpected_still500InternalError_regression() {
        // 兜底处理器回归护栏：普通未知异常仍 500 INTERNAL_ERROR（不被 D3 归一影响）
        ResponseEntity<ApiError> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void toApiError_sseStreamTimeout_mapsToSseTimeoutCode() {
        ApiError apiError = GlobalExceptionHandler.toApiError(
                new SseStreamTimeoutException("模型响应超时，已生成内容可能不完整"));

        assertThat(apiError.code()).isEqualTo("SSE_TIMEOUT");
        assertThat(apiError.message()).isEqualTo("模型响应超时，已生成内容可能不完整");
        assertThat(apiError.timestamp()).isNotBlank();
    }
}
