package com.dj.ai.agentchat.tool.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 请求级工具桥接器（插入迭代 G，S1/S4）：每次对话请求新建一个实例，经 ToolContext
 * 透传给本次请求的所有 {@code DbToolCallback}。承担两职：
 *
 * <ol>
 *   <li><b>事件桥接</b>：工具线程（boundedElastic）上 publish started/终态事件；
 *       流式路径由 ChatController 挂 sink 转发为 SSE event:tool 帧，同步路径 sink=noop。
 *       sink 调用全 try/catch——发送失败（客户端断连）不影响工具执行；流终止四条路径
 *       {@link #detach()} 后事件丢弃（abort 后自然结束的工具不再发帧，AC-69）。</li>
 *   <li><b>幂等缓存</b>：Flux.defer 重订阅会重跑整轮（含工具），同 dedupKey 命中缓存
 *       直接返回结果文本——不重复执行、不重复审计、不重复发帧（S4）。</li>
 * </ol>
 */
public class ToolCallBridge {

    /**
     * 一次工具调用的缓存结果（回传模型的最终文本）+ 证据字段（迭代8）。
     *
     * @param resultText     回传模型的最终文本（含 {@code <tool-result>} 包裹）
     * @param toolName       工具名（证据用；null = 无证据语义，不记录）
     * @param status         SUCCESS / FAILED / TIMEOUT（失败/超时照常记录）
     * @param durationMs     执行耗时
     * @param argsSummary    已脱敏 + 截断的入参摘要
     * @param resultExcerpt  已脱敏 + 截断的结果正文（不含 {@code <tool-result>} 包裹）
     */
    public record CachedOutcome(String resultText,
                                String toolName,
                                String status,
                                long durationMs,
                                String argsSummary,
                                String resultExcerpt) {
        /** 兼容既有单参调用（无证据语义，既有调用点零改动）。 */
        public CachedOutcome(String resultText) {
            this(resultText, null, null, 0L, null, null);
        }
    }

    private final Map<String, CachedOutcome> cache = new ConcurrentHashMap<>();

    /** 证据收集器（迭代8）：默认 null = 不收集；volatile 保证工具线程即时看到 attach。 */
    private volatile ToolEvidenceCollector evidenceCollector;

    // 默认 noop（同步路径）；volatile 保证工具线程即时看到 detach
    private volatile Consumer<ToolEvent> sink = event -> {
    };

    /** 挂接事件 sink（流式路径由 ChatController 调用）。 */
    public void setSink(Consumer<ToolEvent> sink) {
        this.sink = sink == null ? event -> {
        } : sink;
    }

    /** 流终止（done/error/timeout/onCompletion/onError）后摘除 sink，后续事件丢弃。 */
    public void detach() {
        this.sink = event -> {
        };
    }

    /** 发布事件；sink 抛任何异常都吞掉（客户端断连不影响工具执行）。 */
    public void publish(ToolEvent event) {
        try {
            sink.accept(event);
        } catch (Throwable t) {
            // 发送失败不影响工具执行与审计
        }
    }

    /** 查幂等缓存；未命中返回 null。 */
    public CachedOutcome lookup(String dedupKey) {
        return cache.get(dedupKey);
    }

    /**
     * 挂接证据收集器（迭代8；ChatService 在开关开启且有 bridge 时于工具执行前的装配阶段调用）。
     */
    public void attachEvidenceCollector(ToolEvidenceCollector collector) {
        this.evidenceCollector = collector;
    }

    /**
     * 记幂等缓存（putIfAbsent，防御并发同键）。证据记录与缓存写入同一时机：
     * 仅首次写入成功（putIfAbsent 返回 null）、挂了收集器、且 outcome 带 toolName 时记录一条——
     * Flux.defer 重订阅重跑工具在 lookup() 即早退，永不走到这里，证据天然只记一次。
     * 收集过程任何异常吞掉，绝不阻断工具执行（best-effort 旁路约束）。
     */
    public void remember(String dedupKey, CachedOutcome outcome) {
        boolean first = cache.putIfAbsent(dedupKey, outcome) == null;
        ToolEvidenceCollector collector = this.evidenceCollector;
        if (first && collector != null && outcome.toolName() != null) {
            try {
                collector.record(new ToolEvidenceCollector.Entry(
                        outcome.toolName(), outcome.status(), outcome.durationMs(),
                        outcome.argsSummary(), outcome.resultExcerpt()));
            } catch (Throwable t) {
                // 证据记录失败不影响工具执行与缓存
            }
        }
    }
}
