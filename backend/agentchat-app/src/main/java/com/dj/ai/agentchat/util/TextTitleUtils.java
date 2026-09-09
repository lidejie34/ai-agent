package com.dj.ai.agentchat.util;

/**
 * 标题/预览文本截断纯工具（迭代4，FR-4.2）。无 Spring 依赖、前后端同规则（前端
 * {@code utils/title.ts} 以同一组测试向量锁定，AC-8）。
 *
 * <p>规则：{@code trim} → 换行/连续空白折叠为单空格 → 按 Unicode code point 取前 N 个
 * （emoji 等代理对算 1 个字符，{@link StringBuilder#appendCodePoint} 天然不切断代理对）
 * → 超长追加省略号 {@code …}。
 */
public final class TextTitleUtils {

    /** 会话标题上限（code point 数）。 */
    public static final int TITLE_LIMIT = 20;

    /** 消息预览上限（code point 数）。 */
    public static final int PREVIEW_LIMIT = 30;

    private static final String ELLIPSIS = "…";

    private TextTitleUtils() {
    }

    /**
     * 按 code point 截断：trim → {@code \s+} 折叠为单空格 → 取前 {@code limit} 个 code point；
     * 超出则追加 {@code …}。{@code null} 入参返回空串。
     */
    public static String truncate(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("\\s+", " ");
        // 多取 1 个 code point 用于判定是否超长，避免对整串计数
        int[] cps = s.codePoints().limit(limit + 1L).toArray();
        if (cps.length <= limit) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.append(ELLIPSIS).toString();
    }

    /** 会话标题：首轮用户消息截断至 {@link #TITLE_LIMIT}。 */
    public static String buildTitle(String firstUserMessage) {
        return truncate(firstUserMessage, TITLE_LIMIT);
    }

    /** 消息预览：消息内容截断至 {@link #PREVIEW_LIMIT}。 */
    public static String buildPreview(String content) {
        return truncate(content, PREVIEW_LIMIT);
    }
}
