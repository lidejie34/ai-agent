package com.dj.ai.agentchat.rag.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KbMetaValidator 纯函数单测（迭代10）：白名单字符/长度/数量边界、规整语义
 * （trim/去空/去重保序/空白归 null）、逗号禁令（string_to_array 传参安全前提）。
 */
class KbMetaValidatorTest {

    @Test
    void project_nullAndBlank_becomeNull() {
        assertThat(KbMetaValidator.normalizeProject(null, 64)).isNull();
        assertThat(KbMetaValidator.normalizeProject("", 64)).isNull();
        assertThat(KbMetaValidator.normalizeProject("   ", 64)).isNull();
    }

    @Test
    void project_validChineseEnglishDigitDashUnderscore_trimmed() {
        assertThat(KbMetaValidator.normalizeProject(" 订单域 ", 64)).isEqualTo("订单域");
        assertThat(KbMetaValidator.normalizeProject("order-center_2", 64)).isEqualTo("order-center_2");
        assertThat(KbMetaValidator.normalizeProject("项目A-1_号", 64)).isEqualTo("项目A-1_号");
    }

    @Test
    void project_tooLong_throwsProjectFlag() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> KbMetaValidator.normalizeProject(tooLong, 64))
                .isInstanceOf(KbMetaValidator.KbMetaInvalidException.class)
                .hasMessageContaining("64")
                .extracting(e -> ((KbMetaValidator.KbMetaInvalidException) e).isProject())
                .isEqualTo(true);
        // 边界：恰好 64 通过
        assertThat(KbMetaValidator.normalizeProject("a".repeat(64), 64)).hasSize(64);
    }

    @Test
    void project_illegalChars_rejected() {
        for (String bad : List.of("a,b", "a;b", "a b", "a.b", "a/b", "a\"b", "a'b", "a😀b", "a(b)")) {
            assertThatThrownBy(() -> KbMetaValidator.normalizeProject(bad, 64))
                    .isInstanceOf(KbMetaValidator.KbMetaInvalidException.class)
                    .hasMessageContaining("仅支持");
        }
    }

    @Test
    void tags_nullAndEmpty_becomeEmptyList() {
        assertThat(KbMetaValidator.normalizeTags(null, 8, 32)).isEmpty();
        assertThat(KbMetaValidator.normalizeTags(List.of(), 8, 32)).isEmpty();
    }

    @Test
    void tags_trimDropBlankDedupe_preservingOrder() {
        List<String> tags = KbMetaValidator.normalizeTags(
                java.util.Arrays.asList(" 售后 ", "退货", "", "  ", "售后", null, "物流"), 8, 32);
        assertThat(tags).containsExactly("售后", "退货", "物流");
    }

    @Test
    void tags_overMaxCount_throwsTagsFlag() {
        List<String> nine = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9");
        assertThatThrownBy(() -> KbMetaValidator.normalizeTags(nine, 8, 32))
                .isInstanceOf(KbMetaValidator.KbMetaInvalidException.class)
                .hasMessageContaining("8")
                .extracting(e -> ((KbMetaValidator.KbMetaInvalidException) e).isProject())
                .isEqualTo(false);
        // 去重后 ≤ 上限放行
        List<String> dup = List.of("a", "a", "b");
        assertThat(KbMetaValidator.normalizeTags(dup, 2, 32)).containsExactly("a", "b");
    }

    @Test
    void tags_singleTooLong_throws() {
        String long32 = "标".repeat(33);
        assertThatThrownBy(() -> KbMetaValidator.normalizeTags(List.of(long32), 8, 32))
                .isInstanceOf(KbMetaValidator.KbMetaInvalidException.class)
                .hasMessageContaining("32");
        assertThat(KbMetaValidator.normalizeTags(List.of("标".repeat(32)), 8, 32)).hasSize(1);
    }

    @Test
    void tags_illegalChars_rejected_commaIncluded() {
        for (String bad : List.of("a,b", "a;b", "a b", "a.b", "a\"b", "a😀")) {
            assertThatThrownBy(() -> KbMetaValidator.normalizeTags(List.of(bad), 8, 32))
                    .isInstanceOf(KbMetaValidator.KbMetaInvalidException.class)
                    .hasMessageContaining("仅支持");
        }
    }

    @Test
    void parseTagsParam_commaSeparated_dropEmptySegments() {
        assertThat(KbMetaValidator.parseTagsParam("售后, 退货 ,,物流,", 8, 32))
                .containsExactly("售后", "退货", "物流");
        assertThat(KbMetaValidator.parseTagsParam(null, 8, 32)).isEmpty();
        assertThat(KbMetaValidator.parseTagsParam("  ", 8, 32)).isEmpty();
    }

    @Test
    void joinTags_emptyBecomesNull_elseCommaJoined() {
        assertThat(KbMetaValidator.joinTags(null)).isNull();
        assertThat(KbMetaValidator.joinTags(List.of())).isNull();
        assertThat(KbMetaValidator.joinTags(List.of("a", "b"))).isEqualTo("a,b");
    }
}
