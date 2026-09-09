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

    /** 一次工具调用的缓存结果（回传模型的最终文本）。 */
    public record CachedOutcome(String resultText) {
    }

    private final Map<String, CachedOutcome> cache = new ConcurrentHashMap<>();

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

    /** 记幂等缓存（putIfAbsent，防御并发同键）。 */
    public void remember(String dedupKey, CachedOutcome outcome) {
        cache.putIfAbsent(dedupKey, outcome);
    }
}
