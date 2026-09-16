package com.dj.ai.agentchat.rag.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识库维度元数据校验器（迭代10）：admin 写侧（上传/PATCH）与 chat 检索过滤侧共用。
 *
 * <p>白名单：中文/字母/数字/中划线/下划线——<b>禁逗号是硬约束</b>：检索 SQL 以
 * {@code string_to_array(?, ',')} 传标签集合，元素含逗号会被拆段失真。
 * 规整语义：trim、去空、去重（保序）；project 空白归一为 null（未打标）。
 * 校验失败抛 {@link KbMetaInvalidException}，由调用侧映射各自错误码
 * （admin → KB_INVALID_PROJECT/KB_INVALID_TAGS，chat → 400 KB_INVALID_FILTER）。
 */
public final class KbMetaValidator {

    private static final Pattern TOKEN = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9_-]+$");

    private KbMetaValidator() {
    }

    /** 元数据非法（维度 project/tags 由 {@link #isProject()} 区分）。 */
    public static final class KbMetaInvalidException extends RuntimeException {
        private final boolean project;

        private KbMetaInvalidException(boolean project, String message) {
            super(message);
            this.project = project;
        }

        public boolean isProject() {
            return project;
        }
    }

    /**
     * 规整项目名：null/空白 → null（未打标）；超长/非法字符 → KbMetaInvalidException(project)。
     */
    public static String normalizeProject(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.length() > maxLength) {
            throw new KbMetaInvalidException(true,
                    "项目名最长 " + maxLength + " 个字符，当前 " + v.length() + " 个");
        }
        if (!TOKEN.matcher(v).matches()) {
            throw new KbMetaInvalidException(true,
                    "项目名仅支持中文/字母/数字/中划线/下划线，不支持逗号等特殊字符：" + v);
        }
        return v;
    }

    /**
     * 规整项目集合（迭代11 聊天页项目多选）：null → 空列表；逐元素 trim、去空、
     * 去重（保序），每个元素按 {@link #normalizeProject} 同口径校验
     * （长度/字符越界 → KbMetaInvalidException(project)）。
     * 元素已禁逗号，检索 SQL 可安全 string_to_array 拼接（同标签）。
     */
    public static List<String> normalizeProjects(Collection<String> raw, int maxLength) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String item : raw) {
            String v = normalizeProject(item, maxLength);
            if (v != null) {
                cleaned.add(v);
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * 规整标签集合：null → 空列表；逐元素 trim、去空、去重（保序）；
     * 数量/长度/字符越界 → KbMetaInvalidException(非 project)。
     */
    public static List<String> normalizeTags(Collection<String> raw, int maxCount, int maxLength) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            cleaned.add(item.trim());
        }
        if (cleaned.size() > maxCount) {
            throw new KbMetaInvalidException(false,
                    "标签最多 " + maxCount + " 个，去重后仍有 " + cleaned.size() + " 个");
        }
        for (String tag : cleaned) {
            if (tag.length() > maxLength) {
                throw new KbMetaInvalidException(false,
                        "单个标签最长 " + maxLength + " 个字符：「" + tag + "」当前 " + tag.length() + " 个");
            }
            if (!TOKEN.matcher(tag).matches()) {
                throw new KbMetaInvalidException(false,
                        "标签仅支持中文/字母/数字/中划线/下划线，不支持逗号等特殊字符：「" + tag + "」");
            }
        }
        return List.copyOf(cleaned);
    }

    /** 逗号分隔表单字段 → 规整标签集合（空段忽略，等价 normalizeTags(split)）。 */
    public static List<String> parseTagsParam(String commaJoined, int maxCount, int maxLength) {
        if (commaJoined == null || commaJoined.isBlank()) {
            return List.of();
        }
        String[] parts = commaJoined.split(",");
        List<String> raw = new ArrayList<>(parts.length);
        for (String part : parts) {
            raw.add(part);
        }
        return normalizeTags(raw, maxCount, maxLength);
    }

    /** 标签集合 → 逗号拼接（检索 SQL string_to_array 参数；元素已禁逗号，安全）。 */
    public static String joinTags(Collection<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }
}
