package com.dj.ai.agentchat.tool.handler.builtin;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5：analyze_log_errors 全能力（AC-19~26、53、57）——@TempDir 合成日志夹具，全部离线。
 */
class LogAnalysisBuiltinToolTest {

    @TempDir
    Path tempDir;

    private ToolProperties properties;
    private LogAnalysisBuiltinTool tool;
    private final ToolExecutionContext ctx = new ToolExecutionContext("sess-1", "req-1", 30000, 8000);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private String logLine(LocalDateTime ts, String level, String msg) {
        return String.format("%s  %5s 12345 --- [test-thread] c.d.a.TestService : %s",
                ts.format(TS_FMT), level, msg);
    }

    private String stack(LocalDateTime ts, String level, String msg, String exceptionClass,
                         int stackLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(logLine(ts, level, msg)).append('\n');
        sb.append(exceptionClass).append(": 发生错误\n");
        for (int i = 0; i < stackLines; i++) {
            sb.append("    at com.dj.ai.agentchat.TestService.method(TestService.java:").append(i + 1).append(")\n");
        }
        return sb.toString();
    }

    private void writeLog(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }

    private ToolExecutionResult run(Map<String, Object> args) {
        return tool.execute(args, ctx);
    }

    private Map<String, Object> args(int minutes) {
        return Map.of("minutes", minutes);
    }

    @BeforeEach
    void setUp() {
        properties = new ToolProperties();
        properties.getBuiltin().setLogDir(tempDir.toString());
        tool = new LogAnalysisBuiltinTool(properties, new SecretRedactor(List.of()));
    }

    // ---------- 参数校验（AC-19） ----------

    @Test
    void missingMinutes_invalidArgs() {
        ToolExecutionResult r = run(Map.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("INVALID_ARGS");
    }

    @Test
    void minutesOutOfRange_invalidArgs() {
        assertThat(run(Map.of("minutes", 0)).errorCode()).isEqualTo("INVALID_ARGS");
        assertThat(run(Map.of("minutes", 1441)).errorCode()).isEqualTo("INVALID_ARGS");
        assertThat(run(Map.of("minutes", "abc")).errorCode()).isEqualTo("INVALID_ARGS");
    }

    @Test
    void topNOutOfRange_invalidArgs() {
        assertThat(run(Map.of("minutes", 30, "topN", 0)).errorCode()).isEqualTo("INVALID_ARGS");
        assertThat(run(Map.of("minutes", 30, "topN", 51)).errorCode()).isEqualTo("INVALID_ARGS");
    }

    @Test
    void fileNameWithPathSeparator_invalidArgs() {
        assertThat(run(Map.of("minutes", 30, "fileName", "../a.log")).errorCode()).isEqualTo("INVALID_ARGS");
        assertThat(run(Map.of("minutes", 30, "fileName", "sub/a.log")).errorCode()).isEqualTo("INVALID_ARGS");
    }

    // ---------- 目录限定（AC-20） ----------

    @Test
    void logDirMissing_returnsEmptyResultWithNote() {
        properties.getBuiltin().setLogDir(tempDir.resolve("no-such-dir").toString());

        ToolExecutionResult r = run(args(30));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("日志目录不存在").contains("ERROR=0");
    }

    @Test
    void fileNameNotExist_returnsEmptyResultWithNote() throws IOException {
        writeLog("a.log", logLine(LocalDateTime.now().minusMinutes(1), "INFO", "hi"));

        ToolExecutionResult r = run(Map.of("minutes", 30, "fileName", "missing.log"));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("指定文件不存在");
    }

    @Test
    void fileName_scansOnlyThatFile() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        writeLog("a.log", logLine(now.minusMinutes(1), "ERROR", "a 文件错误") + "\n");
        writeLog("b.log", logLine(now.minusMinutes(1), "ERROR", "b 文件错误") + "\n");

        ToolExecutionResult r = run(Map.of("minutes", 30, "fileName", "a.log"));

        assertThat(r.text()).contains("ERROR=1");
    }

    // ---------- 时间窗 / mtime / 无法解析行（AC-21） ----------

    @Test
    void windowCounts_mtimeSkip_unparsedLines() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append("这是一行无法解析时间戳的垃圾行\n"); // unparsed 条目
        sb.append(logLine(now.minusMinutes(2), "ERROR", "普通错误一")).append('\n');
        sb.append(stack(now.minusMinutes(3), "ERROR", "处理失败",
                "java.lang.NullPointerException", 3));
        sb.append(logLine(now.minusMinutes(4), "WARN", "警告一")).append('\n');
        sb.append(logLine(now.minusMinutes(5), "WARN", "警告二")).append('\n');
        sb.append(logLine(now.minusMinutes(6), "WARN", "警告三")).append('\n');
        sb.append(logLine(now.minusMinutes(7), "INFO", "普通信息")).append('\n');
        sb.append(logLine(now.minusMinutes(120), "ERROR", "窗外老错误")).append('\n');
        writeLog("app.log", sb.toString());

        ToolExecutionResult r = run(args(30));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("ERROR=2").contains("WARN=3").contains("含异常堆栈=1");
        assertThat(r.text()).contains("无法解析时间戳的条目: 1");
        assertThat(r.text()).contains("NullPointerException");
        assertThat(r.text()).doesNotContain("窗外老错误");
    }

    @Test
    void mtimeOlderThanWindow_fileSkippedEntirely() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path f = tempDir.resolve("old.log");
        Files.writeString(f, logLine(now.minusMinutes(1), "ERROR", "内容很新但 mtime 很旧") + "\n");
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        ToolExecutionResult r = run(args(30));

        assertThat(r.text()).contains("ERROR=0");
        assertThat(r.text()).contains("跳过 mtime 过期 1 个");
    }

    // ---------- 统计分组（AC-22/23） ----------

    @Test
    void exceptionGroups_normalizedAndRankedByCount_topN() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append(stack(now.minusMinutes(8), "ERROR", "失败",
                "java.lang.NullPointerException: 用户 id=12345 处理失败", 2));
        sb.append(stack(now.minusMinutes(6), "ERROR", "失败",
                "java.lang.NullPointerException: 用户 id=67890 处理失败", 2));
        sb.append(stack(now.minusMinutes(4), "ERROR", "失败",
                "java.lang.IllegalStateException: 状态非法 uuid=550e8400-e29b-41d4-a716-446655440000", 2));
        writeLog("app.log", sb.toString());

        ToolExecutionResult all = run(Map.of("minutes", 30, "topN", 10));
        assertThat(all.text()).contains("出现 2 次").contains("出现 1 次");
        assertThat(all.text()).contains("NullPointerException").contains("IllegalStateException");
        // 归一化：归一化模式行中数字/UUID 被抹除，两条 NPE 合为一组（原始堆栈片段不归一化）
        List<String> patternLines = all.text().lines()
                .filter(l -> l.contains("归一化模式")).toList();
        assertThat(patternLines).hasSize(2);
        assertThat(patternLines.get(0)).contains("id=#").doesNotContain("12345").doesNotContain("67890");
        assertThat(patternLines.stream().filter(l -> l.contains("IllegalStateException")).findFirst().orElseThrow())
                .contains("<uuid>");

        ToolExecutionResult top1 = run(Map.of("minutes", 30, "topN", 1));
        assertThat(top1.text()).contains("### 异常分组 Top 1");
        assertThat(top1.text()).contains("NullPointerException");
        assertThat(top1.text()).doesNotContain("IllegalStateException");
    }

    @Test
    void stackSnippet_cappedAt15Lines() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        writeLog("app.log", stack(now.minusMinutes(2), "ERROR", "boom",
                "java.lang.NullPointerException", 25));

        ToolExecutionResult r = run(args(30));

        String text = r.text();
        int fenceStart = text.indexOf("```");
        int fenceEnd = text.indexOf("```", fenceStart + 3);
        assertThat(fenceStart).isGreaterThan(0);
        // 围栏间内容：去掉开闭围栏行后，非空行即堆栈片段（≤15 行）
        long snippetLines = text.substring(fenceStart + 3, fenceEnd)
                .lines().filter(l -> !l.isBlank()).count();
        assertThat(snippetLines).isLessThanOrEqualTo(15);
    }

    // ---------- 上限截断（AC-26） ----------

    @Test
    void bytesCap_marksTruncated() throws IOException {
        properties.getBuiltin().setScanMaxBytesPerFile(300);
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(logLine(now.minusMinutes(1), "INFO", "重复日志行内容 ".repeat(5))).append('\n');
        }
        writeLog("app.log", sb.toString());

        ToolExecutionResult r = run(args(30));

        assertThat(r.text()).contains("truncated: true").contains("字节触顶");
    }

    @Test
    void linesCap_marksTruncated() throws IOException {
        properties.getBuiltin().setScanMaxLines(5);
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append(logLine(now.minusMinutes(1), "INFO", "一行日志")).append('\n');
        }
        writeLog("app.log", sb.toString());

        ToolExecutionResult r = run(args(30));

        assertThat(r.text()).contains("truncated: true").contains("行数触顶");
    }

    @Test
    void filesCap_marksTruncated() throws IOException {
        properties.getBuiltin().setScanMaxFiles(2);
        LocalDateTime now = LocalDateTime.now();
        for (String name : List.of("a.log", "b.log", "c.log")) {
            writeLog(name, logLine(now.minusMinutes(1), "INFO", name) + "\n");
        }

        ToolExecutionResult r = run(args(30));

        assertThat(r.text()).contains("truncated: true").contains("文件数触顶");
    }

    @Test
    void veryLongLine_doesNotBlowUp() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        writeLog("app.log", logLine(now.minusMinutes(1), "ERROR", "X".repeat(100_000)) + "\n");

        ToolExecutionResult r = run(args(30));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("ERROR=1");
    }

    // ---------- 安全（AC-25/53） ----------

    @Test
    void secretsInLog_areRedacted() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        writeLog("app.log", stack(now.minusMinutes(2), "ERROR", "泄露",
                "java.lang.IllegalStateException: 密钥 ark-abcdefgh12345678XYZ 已打印", 2));

        ToolExecutionResult r = run(args(30));

        assertThat(r.text()).contains(SecretRedactor.REDACTED);
        assertThat(r.text()).doesNotContain("ark-abcdefgh12345678XYZ");
    }

    @Test
    void sourceHasNoProcessBuilder_pureJdk() throws IOException {
        // AC-25：不使用 ProcessBuilder/外部命令（静态检查源码无引用）
        Path src = Path.of("src/main/java/com/dj/ai/agentchat/tool/handler/builtin/LogAnalysisBuiltinTool.java");
        String source = Files.readString(src);
        assertThat(source).doesNotContain("ProcessBuilder").doesNotContain("Runtime.getRuntime");
    }
}
