package com.dj.ai.agentchat.tool.schema;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7：指南内容与渐进披露（AC-34/35）——两份 skills 指南随 jar 发布、
 * 内容为完整方法论（何时使用/参数/结果解读/汇报写法/边界），
 * 由 ToolSeeder 载入 guide_md，DbToolCallback 在结果中前置注入（T4 已断言注入）。
 */
class ToolGuideResourceTest {

    private String readGuide(String classpath) throws Exception {
        try (var in = new ClassPathResource(classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void analyzeLogErrorsGuide_isFullMethodology() throws Exception {
        String guide = readGuide("skills/analyze_log_errors.md");

        assertThat(guide).isNotBlank();
        assertThat(guide).contains("何时使用");
        assertThat(guide).contains("minutes");
        assertThat(guide).contains("truncated");
        assertThat(guide).contains("向用户汇报");
        // 边界说明：只看配置目录
        assertThat(guide).contains("日志目录");
        // 指南中不得包含真实密钥形态（ark- 前缀 + 8 位以上 token）
        assertThat(guide).doesNotContainPattern("ark-[A-Za-z0-9\\-_]{8,}");
    }

    @Test
    void logErrorCountGuide_isFullMethodology() throws Exception {
        String guide = readGuide("skills/log_error_count.md");

        assertThat(guide).isNotBlank();
        assertThat(guide).contains("log_error_count");
        assertThat(guide).contains("minutes");
        assertThat(guide).doesNotContainPattern("ark-[A-Za-z0-9\\-_]{8,}");
    }

    @Test
    void guides_areLoadedBySeeder_withNonBlankContent() {
        // ToolSeeder 从同一 classpath 位置载入 guide_md（读取失败为 null 会 WARN）
        assertThat(ToolSeeder.loadGuide("skills/analyze_log_errors.md")).isNotBlank();
        assertThat(ToolSeeder.loadGuide("skills/log_error_count.md")).isNotBlank();
        assertThat(ToolSeeder.loadGuide("skills/missing-guide.md")).isNull();
    }
}
