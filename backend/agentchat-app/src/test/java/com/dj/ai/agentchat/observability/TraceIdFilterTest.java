package com.dj.ai.agentchat.observability;

import com.dj.ai.agentchat.util.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迭代9 FR-1：{@link TraceIdFilter}——链内 MDC 可见、X-Trace-Id 响应头、
 * finally 后 MDC 清理（正常与异常路径同责，NFR-5）、覆盖 /api/admin 路径。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static FilterChain capturingChain(AtomicReference<String> mdcInside) {
        return (request, response) -> mdcInside.set(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void mdcVisibleInsideChain_andHeaderSet_andCleanedAfter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInside = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(mdcInside));

        String traceId = mdcInside.get();
        assertThat(traceId).hasSize(16).matches("[0-9a-f]{16}");
        // 响应头与链内 MDC 同值
        assertThat(response.getHeader(TraceIdFilter.RESPONSE_HEADER)).isEqualTo(traceId);
        // finally 后 MDC 已清理（线程复用不串号）
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void adminPath_isAlsoFiltered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tools");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInside = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(mdcInside));

        assertThat(mdcInside.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.RESPONSE_HEADER)).isEqualTo(mdcInside.get());
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void mdcCleanedEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
        // 异常路径响应头仍已设置（filter 在链前写入）
        assertThat(response.getHeader(TraceIdFilter.RESPONSE_HEADER)).isNotBlank();
    }

    @Test
    void eachRequestGetsDistinctTraceId() throws ServletException, IOException {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/chat"),
                new MockHttpServletResponse(), capturingChain(first));
        filter.doFilter(new MockHttpServletRequest("POST", "/api/chat"),
                new MockHttpServletResponse(), capturingChain(second));

        assertThat(first.get()).isNotEqualTo(second.get());
    }
}
