package com.dj.ai.agentchat.tool.mcp.callback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * MCP 工具暴露名归一化（插入迭代4，T3，FR-3/AC-12/AC-13）：
 * 暴露名 = {@code <serverName>_<rawToolName>}，全部小写，非 {@code [a-z0-9_]}
 * 字符（连字符/点/斜杠/大写等）替换为 {@code _}；超长时截断 + 8 位 sha1 后缀保证
 * 唯一且不超过 64 字符（模型工具名惯例上限；审计列已放宽至 128 完整留存）。
 */
public final class McpToolNames {

    /** 暴露名最大长度（模型侧工具名惯例）。 */
    public static final int MAX_NAME_LENGTH = 64;
    /** 超长截断时保留的前缀长度（55 + "_" + 8 位 hash = 64）。 */
    static final int TRUNCATED_PREFIX_LENGTH = MAX_NAME_LENGTH - 1 - 8;

    private McpToolNames() {
    }

    /** 归一化（小写 + 非 [a-z0-9_] 替换为 _）但不做超长截断。 */
    public static String normalized(String serverName, String rawToolName) {
        String combined = (serverName == null ? "" : serverName) + "_"
                + (rawToolName == null ? "" : rawToolName);
        return combined.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /** 暴露名：归一化 + 超长截断（55 前缀 + "_" + 8 位 sha1 = 64 字符）。 */
    public static String expose(String serverName, String rawToolName) {
        String normalized = normalized(serverName, rawToolName);
        if (normalized.length() <= MAX_NAME_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, TRUNCATED_PREFIX_LENGTH)
                + "_" + sha1Hex(normalized).substring(0, 8);
    }

    static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-1 为 JDK 内置算法，不可能缺失
            return "hash" + Integer.toHexString(input.hashCode());
        }
    }
}
