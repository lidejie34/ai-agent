package com.dj.ai.agentchat.rag.chunk;

import com.dj.ai.agentchat.rag.RagProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG 文本切片器（迭代6）：Markdown 标题感知 + 字符窗口/重叠，纯函数易测。
 *
 * <p>两种模式（{@code app.rag.chunk.heading-aware}）：
 * <ul>
 *   <li>标题感知（默认开）：先按 ATX 标题行（行首 1-3 个 #，4-6 个 # 不认）切段，
 *       短段之间贪心打包进 maxChars；单个章节超长则对该章节字符滑窗，
 *       超长章节的首片仍带本章节标题行（滑窗起点 0）；</li>
 *   <li>纯字符模式（.txt 或开关关）：对全文做带 overlap 的字符滑窗。</li>
 * </ul>
 *
 * <p>窗口语义：步长 = maxChars-overlap（重叠区只保留在切片边界处，无内点重复）；
 * 每片 strip 后非空才产出；CRLF 统一为 LF。overlap 构造时夹紧到 [0, maxChars-1]
 * 防零步长死循环。本类无线程状态，参数固定后可并发调用。
 *
 * <p>已知边界（本期接受）：fenced code block 内的 # 标题行会被误判为章节起点，
 * 不影响向量检索正确性，仅切片粒度略小。
 */
public class TextChunker {

    /** 在行首向前看匹配 1-3 个 # 后接空白的 ATX 标题行（不消耗字符，用于分段）。 */
    private static final Pattern HEADING_SPLIT =
            Pattern.compile("(?m)^(?=#{1,3}(?!#)[ \\t])");

    private final int maxChars;
    private final int overlap;
    private final boolean headingAware;

    public TextChunker(RagProperties.Chunk chunkProperties) {
        this(chunkProperties.getMaxChars(), chunkProperties.getOverlap(),
                chunkProperties.isHeadingAware());
    }

    public TextChunker(int maxChars, int overlap, boolean headingAware) {
        this.maxChars = maxChars;
        this.overlap = Math.min(overlap, Math.max(0, maxChars - 1));
        this.headingAware = headingAware;
    }

    /**
     * 切片。空白文件返回空列表；每片长度均 ≤ maxChars；相邻切片最多重叠 overlap 字符。
     */
    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!headingAware) {
            return slidingWindows(normalized);
        }
        return chunkByHeadings(normalized);
    }

    /** 标题感知：切段 → 短段贪心打包 / 超长段滑窗。 */
    private List<String> chunkByHeadings(String content) {
        String[] sections = HEADING_SPLIT.split(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawSection : sections) {
            String section = rawSection.strip();
            if (section.isEmpty()) {
                continue;
            }
            if (section.length() <= maxChars) {
                if (current.length() == 0) {
                    current.append(section);
                } else if (current.length() + 2 + section.length() <= maxChars) {
                    current.append("\n\n").append(section);
                } else {
                    chunks.add(current.toString());
                    current.setLength(0);
                    current.append(section);
                }
            } else {
                // 超长章节：先落当前缓冲，再对该章节滑窗（首片含标题行，由起点 0 自然保证）
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.addAll(slidingWindows(section));
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** 带重叠的字符滑窗；每片 strip，丢弃空白片。 */
    private List<String> slidingWindows(String text) {
        int step = maxChars - overlap;
        List<String> windows = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + maxChars, text.length());
            String window = text.substring(start, end).strip();
            if (!window.isEmpty()) {
                windows.add(window);
            }
            if (end == text.length()) {
                break;
            }
        }
        return windows;
    }
}
