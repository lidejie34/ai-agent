package com.dj.ai.agentchat.observability;

import com.dj.ai.agentchat.util.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * traceId 过滤器（迭代9 FR-1）：每请求统一生成 16-hex traceId → MDC +
 * {@code X-Trace-Id} 响应头；finally 清理 MDC 防线程复用串号（NFR-5）。
 *
 * <p>决策（用户已定）：统一生成，<b>不</b>读入站头（不做跨进程 trace）。
 * 覆盖全路径含 {@code /api/admin/**}（决策 b）；actuator 路径开启后也会过本
 * 过滤器，无害（这些请求日志同样带 ID）。
 *
 * <p>filter 自身零日志——避免改变开启态日志行数的预期管理，集中到既有日志点。
 * 注册在 {@link ObservabilityRuntimeConfig}（条件装配）：关闭态 bean 不存在，
 * 过滤器链与迭代8 完全一致。
 *
 * <p>{@code shouldNotFilterAsyncDispatch} 保持默认 true：SSE 异步派发不重复进入，
 * MDC 跨异步边界由 context-propagation + Reactor 自动上下文传播负责
 * （{@link ObservabilityContextInitializer}）。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名：同步路径客户端经此拿 traceId；SSE 路径 session 帧另有字段。 */
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIds.newTraceId();
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
