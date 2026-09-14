package com.dj.ai.agentchat.tool.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T1（迭代8）：{@link ToolEvidenceCollector} 纯单测——单条 800 截断 + 省略号、换行折叠为
 * " ⏎ "、整轮 4000 硬顶（先到保留 + 省略行 K 正确）、空 render 返回 null、失败状态照记、
 * record 内异常吞掉不抛。
 */
class ToolEvidenceCollectorTest {

    @Test
    void emptyCollector_renderReturnsNull_isEmptyTrue() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);

        assertThat(collector.isEmpty()).isTrue();
        assertThat(collector.renderEvidence()).isNull();
    }

    @Test
    void singleEntry_renderedAsOnePipeSeparatedLine() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        collector.record(new ToolEvidenceCollector.Entry(
                "skyeye_query_log", "SUCCESS", 1230, "{\"uk\":\"xxx\"}", "2026-09-14 ERROR boom"));

        String rendered = collector.renderEvidence();

        assertThat(rendered).isEqualTo(
                "skyeye_query_log | SUCCESS | 1230ms | 入参: {\"uk\":\"xxx\"} | 结果: 2026-09-14 ERROR boom");
        assertThat(collector.isEmpty()).isFalse();
    }

    @Test
    void longExcerpt_truncatedAtMaxCharsPerCall_withEllipsisMark() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(10, 4000);
        collector.record(new ToolEvidenceCollector.Entry(
                "demo_tool", "SUCCESS", 1, "{}", "1234567890ABCDEF"));

        String rendered = collector.renderEvidence();

        assertThat(rendered).contains("结果: 1234567890…");
        assertThat(rendered).doesNotContain("ABCDEF");
    }

    @Test
    void excerptNewlines_collapsedToFoldMarker() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        collector.record(new ToolEvidenceCollector.Entry(
                "demo_tool", "SUCCESS", 1, "{}", "line1\nline2\r\nline3\rline4"));

        String rendered = collector.renderEvidence();

        assertThat(rendered).contains("line1 ⏎ line2 ⏎ line3 ⏎ line4");
        // 整段证据单行呈现：除行分隔符外摘录内不残留换行
        assertThat(rendered).doesNotContain("line1\n");
    }

    @Test
    void turnOverflow_keepsEarliestEntries_andAppendsOmissionLineWithCount() {
        // 单条行 = 前缀 37 + 摘录 100 = 137 字符；硬顶 140 → 第二条（137+1+137）放不下，K=2
        String excerpt = "X".repeat(100);
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 140);
        collector.record(new ToolEvidenceCollector.Entry("tool_a", "SUCCESS", 1, "a", excerpt));
        collector.record(new ToolEvidenceCollector.Entry("tool_b", "SUCCESS", 2, "b", excerpt));
        collector.record(new ToolEvidenceCollector.Entry("tool_c", "FAILED", 3, "c", excerpt));

        String rendered = collector.renderEvidence();

        assertThat(rendered).contains("tool_a | SUCCESS");
        assertThat(rendered).doesNotContain("tool_b");
        assertThat(rendered).doesNotContain("tool_c");
        assertThat(rendered).endsWith("...（本轮另有 2 次工具调用，证据已省略）");
    }

    @Test
    void turnExactlyFits_noOmissionLine() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        collector.record(new ToolEvidenceCollector.Entry("tool_a", "SUCCESS", 1, "a", "ra"));
        collector.record(new ToolEvidenceCollector.Entry("tool_b", "TIMEOUT", 30000, "b", "rb"));

        String rendered = collector.renderEvidence();

        assertThat(rendered).contains("tool_a | SUCCESS").contains("tool_b | TIMEOUT | 30000ms");
        assertThat(rendered).doesNotContain("证据已省略");
    }

    @Test
    void failedAndTimeoutStatus_recordedVerbatim_notDropped() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        collector.record(new ToolEvidenceCollector.Entry(
                "codegraph_locate", "FAILED", 85, "{\"symbol\":\"Foo\"}", "{\"error\":\"INVALID_ARGS\"}"));
        collector.record(new ToolEvidenceCollector.Entry(
                "mysql_query", "TIMEOUT", 30000, "{\"sql\":\"select 1\"}", "工具执行超时（30000ms）"));

        String rendered = collector.renderEvidence();

        assertThat(rendered).contains("codegraph_locate | FAILED | 85ms");
        assertThat(rendered).contains("mysql_query | TIMEOUT | 30000ms");
        assertThat(rendered).contains("INVALID_ARGS");
    }

    @Test
    void recordException_swallowed_neverThrows() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);

        // null entry → 内部 NPE 必须吞掉（best-effort 旁路约束）
        assertThatCode(() -> collector.record(null)).doesNotThrowAnyException();
        assertThat(collector.renderEvidence()).isNull();
    }

    @Test
    void nullFields_renderAsEmptySegments() {
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        collector.record(new ToolEvidenceCollector.Entry("demo_tool", null, 0, null, null));

        String rendered = collector.renderEvidence();

        assertThat(rendered).isEqualTo("demo_tool |  | 0ms | 入参:  | 结果: ");
    }
}
