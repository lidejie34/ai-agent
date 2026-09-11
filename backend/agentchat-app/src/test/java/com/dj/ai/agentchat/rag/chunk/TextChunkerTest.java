package com.dj.ai.agentchat.rag.chunk;

import com.dj.ai.agentchat.rag.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3：TextChunker 密集单测——空文件、纯文本滑窗/重叠/边界、
 * Markdown 标题切段与贪心打包、超长章节滑窗、配置绑定构造。
 */
class TextChunkerTest {

    private TextChunker chunker(int max, int overlap, boolean headingAware) {
        return new TextChunker(max, overlap, headingAware);
    }

    // ---------- 空 / 非法输入 ----------

    @Test
    void chunk_nullOrBlank_returnsEmpty() {
        TextChunker c = chunker(500, 80, true);
        assertThat(c.chunk(null)).isEmpty();
        assertThat(c.chunk("")).isEmpty();
        assertThat(c.chunk("   \n\t \r\n")).isEmpty();
    }

    // ---------- 纯字符滑窗 ----------

    @Test
    void plainText_shorterThanMax_singleTrimmedChunk() {
        TextChunker c = chunker(500, 80, false);
        List<String> chunks = c.chunk("  你好世界  \n");
        assertThat(chunks).containsExactly("你好世界");
    }

    @Test
    void plainText_exactlyMaxChars_singleChunk() {
        TextChunker c = chunker(100, 20, false);
        String text = "a".repeat(100);
        assertThat(c.chunk(text)).containsExactly(text);
    }

    @Test
    void plainText_oneCharBeyondMax_twoChunksWithOverlap() {
        // 101 字：窗 [0,100] 与 [80,101]，重叠 80 字，尾片 21 字
        TextChunker c = chunker(100, 20, false);
        List<String> chunks = c.chunk("a".repeat(101));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(100);
        assertThat(chunks.get(1)).hasSize(21);
    }

    @Test
    void plainText_longText_windowsRespectMaxStepAndOverlap() {
        TextChunker c = chunker(100, 20, false); // step=80
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append((char) ('a' + i % 26));
        }
        List<String> chunks = c.chunk(sb.toString());
        // [0,100) [80,180) [160,250)
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(ch -> assertThat(ch).hasSizeLessThanOrEqualTo(100));
        String text = sb.toString();
        assertThat(chunks.get(0)).isEqualTo(text.substring(0, 100));
        assertThat(chunks.get(1)).isEqualTo(text.substring(80, 180));
        assertThat(chunks.get(2)).isEqualTo(text.substring(160, 250));
        // 邻片重叠区一致
        assertThat(chunks.get(0).substring(80)).isEqualTo(chunks.get(1).substring(0, 20));
    }

    @Test
    void plainText_coversFullContentWithoutInnerDuplicationBeyondOverlap() {
        TextChunker c = chunker(50, 10, false);
        String text = "0123456789".repeat(20); // 200 字
        List<String> chunks = c.chunk(text);
        assertThat(chunks).isNotEmpty();
        // 首片从头开始、末片收尾，拼接覆盖原文
        assertThat(chunks.get(0)).startsWith("0123456789");
        assertThat(chunks.get(chunks.size() - 1)).endsWith("0123456789");
        // 步长 40：200 字 → [0,50)[40,90)[80,130)[120,170)[160,200) = 5 片
        assertThat(chunks).hasSize(5);
    }

    @Test
    void crlf_normalizedToLf() {
        TextChunker c = chunker(500, 80, false);
        List<String> chunks = c.chunk("line1\r\nline2\r");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).doesNotContain("\r").contains("line1\nline2");
    }

    // ---------- 标题感知 ----------

    @Test
    void markdown_shortSections_greedyPackedIntoOneChunk() {
        TextChunker c = chunker(500, 80, true);
        String md = "# 标题一\n\n正文甲。\n\n## 标题二\n\n正文乙。";
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("# 标题一", "正文甲", "## 标题二", "正文乙");
    }

    @Test
    void markdown_sectionsBeyondPackLimit_splitAtHeadingBoundary() {
        // 每段 16 字，两段合并需 34 > max=30 → 在标题边界切两片
        TextChunker c = chunker(30, 5, true);
        String md = "# A\n" + "甲".repeat(12) + "\n\n# B\n" + "乙".repeat(12);
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).startsWith("# A").doesNotContain("# B");
        assertThat(chunks.get(1)).startsWith("# B").doesNotContain("# A");
    }

    @Test
    void markdown_headingLevel4to6_areNotSectionBoundaries() {
        TextChunker c = chunker(500, 80, true);
        String md = "# 一级\n\n正文。\n\n#### 四级标题不切\n\n继续。";
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("#### 四级标题不切");
    }

    @Test
    void markdown_hashWithoutSpace_isNotHeading() {
        TextChunker c = chunker(500, 80, true);
        String md = "#标签一 这不是标题\n\n普通正文。";
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("#标签一");
    }

    @Test
    void markdown_longSection_windowed_firstWindowKeepsHeading() {
        // 单章节 220 字 > max=100：滑窗 3 片，首片以标题行开头
        TextChunker c = chunker(100, 20, true);
        String body = "字".repeat(210);
        String md = "# 超长章节\n" + body;
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).startsWith("# 超长章节");
        assertThat(chunks).allSatisfy(ch -> assertThat(ch).hasSizeLessThanOrEqualTo(100));
        // 标题不出现在后续窗口（起点 94、188 之后，标题已在窗外）
        assertThat(chunks.get(1)).doesNotContain("# 超长章节");
    }

    @Test
    void markdown_leadingBodyBeforeFirstHeading_isItsOwnSection() {
        TextChunker c = chunker(500, 80, true);
        String md = "导语段落。\n\n# 正式标题\n\n正文。";
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("导语段落").contains("# 正式标题");
    }

    @Test
    void headingAwareFalse_hashLinesTreatedAsPlainText() {
        TextChunker c = chunker(500, 80, false);
        String md = "# 标题\n\n正文。";
        List<String> chunks = c.chunk(md);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("# 标题\n\n正文。");
    }

    // ---------- 配置构造与防御 ----------

    @Test
    void constructedFromProperties_bindsValues() {
        RagProperties.Chunk p = new RagProperties.Chunk();
        p.setMaxChars(123);
        p.setOverlap(23);
        p.setHeadingAware(true);
        TextChunker c = new TextChunker(p);
        List<String> chunks = c.chunk("a".repeat(200));
        // step=100：[0,123) [100,200)
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(123);
    }

    @Test
    void overlapClampedToMaxMinusOne_neverLoops() {
        // 恶意配置 overlap ≥ max：夹紧到 max-1（step=1），必须正常返回且不卡死
        TextChunker c = chunker(10, 50, false);
        List<String> chunks = c.chunk("abcdefghijXYZ"); // 13 字
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(ch -> assertThat(ch).hasSizeLessThanOrEqualTo(10));
        assertThat(chunks.get(chunks.size() - 1)).endsWith("XYZ");
    }
}
