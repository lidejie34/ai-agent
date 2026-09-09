package com.dj.ai.agentchat.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1：{@link TextTitleUtils} 标题/预览截断工具单测。
 *
 * <p>本类为<b>前后端共享测试向量</b>的后端半（AC-8）：前端 {@code utils/title.test.ts}
 * 以同输入同期望锁定同一规则（trim → 空白折叠 → code point 截取 → 超长加 …，代理对安全）。
 */
class TextTitleUtilsTest {

    @Test
    void nullInput_returnsEmpty() {
        assertThat(TextTitleUtils.truncate(null, 20)).isEmpty();
        assertThat(TextTitleUtils.buildTitle(null)).isEmpty();
        assertThat(TextTitleUtils.buildPreview(null)).isEmpty();
    }

    @Test
    void trimAndCollapseWhitespace_noEllipsisWhenShort() {
        // trim + 换行/连续空白折叠为单空格；未超长不加省略号
        assertThat(TextTitleUtils.buildTitle("  你好   世界\n你好 "))
                .isEqualTo("你好 世界 你好");
    }

    @Test
    void mixedWhitespaceTabCrLf_allFoldedToSingleSpace() {
        assertThat(TextTitleUtils.truncate("a\tb\r\nc   d\te", 20))
                .isEqualTo("a b c d e");
    }

    @Test
    void exactlyLimitCodePoints_noEllipsis() {
        String twentyHanzi = "汉".repeat(20);
        assertThat(TextTitleUtils.buildTitle(twentyHanzi)).isEqualTo(twentyHanzi);
        assertThat(TextTitleUtils.buildTitle(twentyHanzi)).doesNotContain("…");
    }

    @Test
    void overLimit_byOneCodePoint_appendsEllipsis() {
        String twentyOneHanzi = "汉".repeat(21);
        String result = TextTitleUtils.buildTitle(twentyOneHanzi);
        assertThat(result).isEqualTo("汉".repeat(20) + "…");
        // 省略号前正文恰好 20 个 code point
        String body = result.substring(0, result.length() - 1);
        assertThat(body.codePointCount(0, body.length())).isEqualTo(20);
    }

    @Test
    void emojiCountedAsOneCodePoint_exactlyTwentyNoEllipsis() {
        // "我喜欢😀" × 5 = 15 汉字 + 5 emoji = 20 code point（UTF-16 为 25 char）
        String input = "我喜欢😀".repeat(5);
        assertThat(input.length()).isEqualTo(25); // 代理对：emoji 各占 2 char
        assertThat(input.codePointCount(0, input.length())).isEqualTo(20);
        assertThat(TextTitleUtils.buildTitle(input)).isEqualTo(input);
        assertThat(TextTitleUtils.buildTitle(input)).doesNotContain("…");
    }

    @Test
    void overLimitWithEmoji_trailingSurrogateNotSplit() {
        // AC-8 向量：20 汉字 + 5 emoji = 25 code point → 前 20 cp + …（边界在汉字/emoji 之间）
        String twentyHanziFiveEmoji = "汉".repeat(20) + "😀".repeat(5);
        assertThat(twentyHanziFiveEmoji.codePointCount(0, twentyHanziFiveEmoji.length())).isEqualTo(25);
        String r1 = TextTitleUtils.buildTitle(twentyHanziFiveEmoji);
        assertThat(r1).isEqualTo("汉".repeat(20) + "…");

        // 更强向量：19 汉字 + 6 emoji = 25 cp；naive 按 char 截 20 会切断首个 emoji 的高代理项，
        // code point 截取须保留完整 emoji（前 20 cp = 19 汉字 + 1 个完整 emoji）
        String boundaryHitsEmoji = "汉".repeat(19) + "😀".repeat(6);
        assertThat(boundaryHitsEmoji.codePointCount(0, boundaryHitsEmoji.length())).isEqualTo(25);
        String r2 = TextTitleUtils.buildTitle(boundaryHitsEmoji);
        assertThat(r2).endsWith("…");
        String body = r2.substring(0, r2.length() - 1);
        assertThat(body.codePointCount(0, body.length())).isEqualTo(20);
        assertThat(body).isEqualTo("汉".repeat(19) + "😀");
        // 省略号前一个 char 不得是孤立高代理项（代理对天然不被切断）
        char lastChar = body.charAt(body.length() - 1);
        assertThat(Character.isHighSurrogate(lastChar)).isFalse();
        // 末尾 code point 为完整 emoji（round-trip 安全）
        assertThat(body.codePointBefore(body.length())).isEqualTo(0x1F600);
    }

    @Test
    void previewLimit_isThirty_withSameRule() {
        // 31 code point → 前 30 + …
        String thirtyOne = "字".repeat(31);
        assertThat(TextTitleUtils.buildPreview(thirtyOne)).isEqualTo("字".repeat(30) + "…");
        // 恰好 30 不省略
        String thirty = "字".repeat(30);
        assertThat(TextTitleUtils.buildPreview(thirty)).isEqualTo(thirty);
    }

    @Test
    void limits_arePinnedConstants() {
        assertThat(TextTitleUtils.TITLE_LIMIT).isEqualTo(20);
        assertThat(TextTitleUtils.PREVIEW_LIMIT).isEqualTo(30);
    }
}
