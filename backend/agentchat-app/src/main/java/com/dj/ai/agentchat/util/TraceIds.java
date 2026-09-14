package com.dj.ai.agentchat.util;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * traceId 工具（迭代9 FR-1）：请求级追踪 ID 的生成与 MDC 读写。
 *
 * <p>形态：UUID 去 dash 取前 16 位小写 hex——本地自用单进程，碰撞概率可忽略；
 * 审计列宽兼容已核实（agent_tool_call_log.call_id VARCHAR(160) /
 * agent_orchestration_run.run_id VARCHAR(64)），零 DDL。
 * 若评审要求全 UUID（36 位），仅改 {@link #newTraceId()} 一处，下游零影响。
 *
 * <p>关闭态（app.observability.enabled=false）：TraceIdFilter 不装配 → MDC 恒空，
 * {@link #currentOrNew()} 走 UUID 分支，工具 requestId 行为与迭代8 逐字节一致。
 */
public final class TraceIds {

    /** MDC 键：日志 pattern 经 {@code %X{traceId}} 引用同一键。 */
    public static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    /** 生成新 traceId：UUID.randomUUID() 去 dash 取前 16 位小写 hex。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 当前线程 MDC 中的 traceId；无（关闭态/非请求线程）返回 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 工具 requestId 复用点（DefaultToolSupport）：MDC 有 traceId 则复用
     * （开启态 requestId==traceId，一个 ID grep 全链）；MDC 空（关闭态/非请求线程）
     * 回退新 traceId——与迭代8 的 UUID.randomUUID() 同为无格式假设的唯一串。
     */
    public static String currentOrNew() {
        String id = MDC.get(MDC_KEY);
        return StringUtils.hasText(id) ? id : newTraceId();
    }
}
