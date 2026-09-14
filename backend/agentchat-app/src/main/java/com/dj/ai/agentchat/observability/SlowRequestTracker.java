package com.dj.ai.agentchat.observability;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 慢请求环形清单（迭代9 FR-4）：HTTP 请求级（同步对话 / SSE 订阅到流结束）
 * 超阈值请求的有界内存记录，重启丢失，本地自用。
 *
 * <ul>
 *   <li>判定：{@code thresholdMs <= 0} 一律不记（FR-4.2）；{@code durationMs >= thresholdMs} 入列；</li>
 *   <li>结构：{@link ArrayDeque} 环形语义，写满淘汰最旧；容量默认 100；</li>
 *   <li>入列同时打 warn（FR-4.6）：traceId / path / 耗时 / 终态四要素 + 起始时刻，
 *       <b>不记录任何正文/参数</b>（FR-6.2）；</li>
 *   <li>线程安全：synchronized 方法（记录频率极低，无锁竞争顾虑）。</li>
 * </ul>
 *
 * 「模型耗时占比」（FR-4.3 若可得）：v1 不落——采集需穿透 Advisor↔Controller 边界，
 * 成本大于收益，列为可选后续。
 */
@Slf4j
public class SlowRequestTracker {

    /** 慢请求记录：traceId / 路径 / 起始时刻（ISO 秒级，本地时区）/ 耗时毫秒 / 终态。 */
    public record SlowRequestEntry(String traceId, String path, String startTimeIso,
                                   long durationMs, String outcome) {
    }

    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final long thresholdMs;
    private final int capacity;
    private final ArrayDeque<SlowRequestEntry> entries;

    public SlowRequestTracker(long thresholdMs, int capacity) {
        this.thresholdMs = thresholdMs;
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    /**
     * 超阈值则入列并打 warn，返回是否已记录。
     * {@code traceId} 可空（关闭态不装配本 bean，调用方已判空；防御性接受 null）。
     */
    public synchronized boolean recordIfSlow(String traceId, String path, long durationMs,
                                             String outcome) {
        if (thresholdMs <= 0 || durationMs < thresholdMs) {
            return false;
        }
        SlowRequestEntry entry = new SlowRequestEntry(traceId, path,
                LocalDateTime.now().format(ISO_SECONDS), durationMs, outcome);
        while (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        log.warn("慢请求: traceId={}, path={}, 耗时={}ms, 终态={}", traceId, path, durationMs, outcome);
        return true;
    }

    /** 快照：新 → 旧副本（读取不阻塞写入口径，返回独立 List）。 */
    public synchronized List<SlowRequestEntry> snapshot() {
        List<SlowRequestEntry> copy = new ArrayList<>(entries);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public long thresholdMs() {
        return thresholdMs;
    }

    public int capacity() {
        return capacity;
    }

    public synchronized int size() {
        return entries.size();
    }
}
