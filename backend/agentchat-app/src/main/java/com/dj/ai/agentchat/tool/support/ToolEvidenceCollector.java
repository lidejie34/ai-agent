package com.dj.ai.agentchat.tool.support;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一轮对话的工具查证证据收集器（迭代8）：请求级对象，由 ChatService 在开关开启时创建并挂到
 * {@link ToolCallBridge}；工具线程（boundedElastic）与对话线程可能并发写，故用
 * {@link CopyOnWriteArrayList}（每工具一次低频写，写时复制开销可忽略）。
 *
 * <p>职责只有两个：
 * <ol>
 *   <li>{@link #record(Entry)}：登记一次工具调用的结构化证据。入侧即做「换行折叠 +
 *       单条摘录截断（maxCharsPerCall）」；任何异常吞掉仅 warn，绝不阻断工具执行；</li>
 *   <li>{@link #renderEvidence()}：把本轮全部证据渲染成一段文本（一条证据消息的内容）。
 *       整轮硬顶 maxCharsPerTurn：先到记录优先保留，放不下的整条舍弃并在末尾追加
 *       {@code ...（本轮另有 K 次工具调用，证据已省略）}；无证据返回 {@code null}。</li>
 * </ol>
 *
 * <p>渲染格式（每次工具调用一行，管道分隔五段）：
 * {@code toolName | STATUS | 123ms | 入参: {...} | 结果: 摘录}
 */
@Slf4j
public class ToolEvidenceCollector {

    /** 换行折叠标记：单行阅读友好，避免摘录里的换行撑破「一次调用一行」的行格式。 */
    static final String NEWLINE_FOLD = " ⏎ ";
    /** 单条摘录截断标记。 */
    static final String TRUNCATED_MARK = "…";

    /**
     * 单条工具调用证据。resultExcerpt 为已脱敏 + 已截断的结果正文
     * （不含 {@code <tool-result>} 包裹——包裹由 Callback 在证据摘录之后拼接）。
     */
    public record Entry(String toolName, String status, long durationMs,
                        String argsSummary, String resultExcerpt) {
    }

    private final int maxCharsPerCall;
    private final int maxCharsPerTurn;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public ToolEvidenceCollector(int maxCharsPerCall, int maxCharsPerTurn) {
        this.maxCharsPerCall = maxCharsPerCall;
        this.maxCharsPerTurn = maxCharsPerTurn;
    }

    /**
     * 记录一条证据；任何异常吞掉（best-effort），绝不抛出阻断工具执行。
     */
    public void record(Entry entry) {
        try {
            entries.add(new Entry(entry.toolName(), entry.status(), entry.durationMs(),
                    entry.argsSummary(), collapseAndTruncate(entry.resultExcerpt())));
        } catch (Throwable t) {
            log.warn("证据记录失败（不影响工具执行）: {}", t.getMessage());
        }
    }

    /**
     * 渲染本轮证据为一段文本；无证据返回 {@code null}（调用方据此不插入证据消息）。
     * 整轮超 maxCharsPerTurn 时先到记录优先保留，末尾追加省略行（K = 未收录条数）。
     */
    public String renderEvidence() {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int included = 0;
        for (Entry entry : entries) {
            String line = renderLine(entry);
            // 首条恒收录；后续条目加入会越整轮硬顶时停止收录（含行分隔换行的 1 字符）
            if (included > 0 && sb.length() + 1 + line.length() > maxCharsPerTurn) {
                break;
            }
            if (included > 0) {
                sb.append('\n');
            }
            sb.append(line);
            included++;
        }
        int omitted = entries.size() - included;
        if (omitted > 0) {
            sb.append('\n').append("...（本轮另有 ").append(omitted)
                    .append(" 次工具调用，证据已省略）");
        }
        return sb.toString();
    }

    /** 本轮是否无任何证据。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static String renderLine(Entry entry) {
        return nullToEmpty(entry.toolName())
                + " | " + nullToEmpty(entry.status())
                + " | " + entry.durationMs() + "ms"
                + " | 入参: " + nullToEmpty(entry.argsSummary())
                + " | 结果: " + nullToEmpty(entry.resultExcerpt());
    }

    /** 换行折叠为 " ⏎ " 后按 maxCharsPerCall 截断（截断标记 …）；null 安全。 */
    private String collapseAndTruncate(String excerpt) {
        if (excerpt == null) {
            return null;
        }
        String collapsed = excerpt
                .replace("\r\n", NEWLINE_FOLD)
                .replace("\n", NEWLINE_FOLD)
                .replace("\r", NEWLINE_FOLD);
        if (collapsed.length() <= maxCharsPerCall) {
            return collapsed;
        }
        return collapsed.substring(0, maxCharsPerCall) + TRUNCATED_MARK;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
