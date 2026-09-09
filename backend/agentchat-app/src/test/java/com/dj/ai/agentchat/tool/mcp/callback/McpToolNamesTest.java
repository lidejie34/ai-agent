package com.dj.ai.agentchat.tool.mcp.callback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 工具暴露名归一化单测（迭代4 T3，AC-12/AC-13）。
 */
class McpToolNamesTest {

    @Test
    void expose_lowercasesAndReplacesNonAlphaNumUnderscore() {
        // server 连字符 + raw 大写/点/连字符 → 全部小写、非 [a-z0-9_] 变 _
        assertThat(McpToolNames.expose("my-fs", "Read.File-V2"))
                .isEqualTo("my_fs_read_file_v2");
        assertThat(McpToolNames.expose("fs", "ECHO")).isEqualTo("fs_echo");
    }

    @Test
    void expose_nullSafe() {
        assertThat(McpToolNames.expose("fs", null)).isEqualTo("fs_");
    }

    @Test
    void expose_longName_truncatedTo64WithHashSuffix() {
        String raw = "A".repeat(100);
        String exposed = McpToolNames.expose("fs", raw);

        assertThat(exposed).hasSize(McpToolNames.MAX_NAME_LENGTH);
        // 55 前缀 + "_" + 8 位 hash
        assertThat(exposed.charAt(55)).isEqualTo('_');
        assertThat(exposed.substring(56)).matches("[0-9a-f]{8}");
        assertThat(exposed.startsWith("fs_" + "a".repeat(51))).isTrue();
    }

    @Test
    void expose_distinctLongNames_remainDistinct() {
        String a = McpToolNames.expose("fs", "A".repeat(100));
        String b = McpToolNames.expose("fs", "B".repeat(100));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void expose_nameExactly64_notTruncated() {
        String raw = "a".repeat(61); // "fs_" 3 + 61 = 64
        String exposed = McpToolNames.expose("fs", raw);
        assertThat(exposed).hasSize(64);
        assertThat(exposed).isEqualTo("fs_" + "a".repeat(61));
    }
}
