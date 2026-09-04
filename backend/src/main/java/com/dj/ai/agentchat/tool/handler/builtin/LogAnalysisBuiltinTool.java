package com.dj.ai.agentchat.tool.handler.builtin;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 内置工具 {@code analyze_log_errors}（插入迭代 G，纯 JDK NIO + 正则，AC-19~26/53/57）：
 * 回溯指定分钟数扫描日志目录，统计 ERROR/WARN/异常堆栈，按归一化消息分组取 TopN，
 * 输出模型可直接转述的 Markdown。
 *
 * <p>安全与边界：
 * <ul>
 *   <li>只扫配置日志目录内的常规文件（NOFOLLOW，不递归、不跟符号链接）；fileName 白名单校验；</li>
 *   <li>文件数 / 单文件字节 / 累计行数三重上限，触顶 truncated 标注（AC-26）；</li>
   <li>mtime 早于窗口起点的文件整体跳过（AC-21）；</li>
 *   <li>无子进程、无外部命令（AC-25）；结果全文经 SecretRedactor 脱敏（AC-53）；</li>
 *   <li>参数非法一律结构化 INVALID_ARGS，内部全捕获不外抛（AC-19/56）。</li>
 * </ul>
 */
@Slf4j
public class LogAnalysisBuiltinTool implements BuiltinTool {

    public static final String KEY = "analyzeLogErrors";

    /** 文件名白名单（与 PathGuard 一致：无路径分隔符、无 ..）。 */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** Spring Boot 默认日志格式条目首行：时间戳 + 级别。 */
    private static final Pattern ENTRY_HEAD = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\b");
    /** 异常类名：全限定名片段 + Exception/Error/Throwable 后缀。 */
    private static final Pattern EXCEPTION_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$.]*(Exception|Error|Throwable)");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX_PATTERN = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d+");

    /** 代表堆栈片段最大行数。 */
    private static final int MAX_STACK_LINES = 15;

    private static final DateTimeFormatter WINDOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ToolProperties properties;
    private final SecretRedactor redactor;

    public LogAnalysisBuiltinTool(ToolProperties properties, SecretRedactor redactor) {
        this.properties = properties;
        this.redactor = redactor;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext ctx) {
        try {
            return doExecute(args == null ? Map.of() : args);
        } catch (Throwable t) {
            // 内置工具承诺全捕获；任何意外（IO/解析）都转结构化失败，绝不炸对话（AC-56）
            log.error("日志分析工具执行异常已兜底", t);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "日志分析失败: " + msg);
        }
    }

    private ToolExecutionResult doExecute(Map<String, Object> args) {
        // ---- 参数校验（AC-19）----
        Integer minutes = intArg(args, "minutes");
        if (minutes == null) {
            return ToolExecutionResult.failed("INVALID_ARGS", "缺少必填参数 minutes（回溯分钟数，1-1440）");
        }
        if (minutes < 1 || minutes > 1440) {
            return ToolExecutionResult.failed("INVALID_ARGS", "minutes 必须为 1-1440 的整数，当前: " + minutes);
        }
        int topN = 10;
        Integer topNArg = intArg(args, "topN");
        if (topNArg != null) {
            if (topNArg < 1 || topNArg > 50) {
                return ToolExecutionResult.failed("INVALID_ARGS", "topN 必须为 1-50 的整数，当前: " + topNArg);
            }
            topN = topNArg;
        }
        String fileName = stringArg(args, "fileName");
        if (fileName != null && !SAFE_FILE_NAME.matcher(fileName).matches()) {
            return ToolExecutionResult.failed("INVALID_ARGS",
                    "fileName 非法：只允许字母/数字/点/下划线/连字符，且不能含路径分隔符: " + fileName);
        }

        ToolProperties.Builtin cfg = properties.getBuiltin();
        Path root = Paths.get(cfg.getLogDir()).toAbsolutePath().normalize();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(minutes);

        ScanState state = new ScanState(windowStart, now);

        // ---- 目录/文件解析（AC-20）----
        List<Path> targets;
        if (fileName != null) {
            Path target = root.resolve(fileName).normalize();
            if (!target.startsWith(root)) {
                return ToolExecutionResult.failed("INVALID_ARGS", "fileName 越出日志目录: " + fileName);
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return ToolExecutionResult.success(redactor.redact(
                        emptyReport(root, minutes, "指定文件不存在或不是常规文件: " + fileName)));
            }
            targets = List.of(target);
        } else {
            if (!Files.isDirectory(root)) {
                return ToolExecutionResult.success(redactor.redact(
                        emptyReport(root, minutes, "日志目录不存在或为空: " + root)));
            }
            targets = listLogFiles(root, cfg, state);
        }

        // ---- 扫描（AC-21/26）----
        int totalLinesBudget = cfg.getScanMaxLines();
        for (Path file : targets) {
            if (state.totalLines >= totalLinesBudget) {
                break;
            }
            scanFile(file, cfg, state, totalLinesBudget);
        }

        return ToolExecutionResult.success(redactor.redact(state.renderMarkdown(root, minutes, topN)));
    }

    /** 列出目录内常规文件（不递归、NOFOLLOW），按 mtime 降序；mtime 过期快路径跳过；文件数触顶截断。 */
    private List<Path> listLogFiles(Path root, ToolProperties.Builtin cfg, ScanState state) {
        record Scanned(Path path, FileTime mtime) {
        }
        List<Scanned> all = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(p -> {
                        try {
                            all.add(new Scanned(p, Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)));
                        } catch (IOException e) {
                            log.warn("读取文件 mtime 失败，跳过: {} ({})", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("列出日志目录失败: {} ({})", root, e.getMessage());
            state.truncate("日志目录读取失败: " + e.getMessage());
            return List.of();
        }
        all.sort(Comparator.comparing(Scanned::mtime).reversed());

        List<Path> result = new ArrayList<>();
        Instant windowStartInstant = state.windowStart.atZone(java.time.ZoneId.systemDefault()).toInstant();
        int skippedMtime = 0;
        for (Scanned s : all) {
            if (s.mtime().toInstant().isBefore(windowStartInstant)) {
                skippedMtime++;
                continue;
            }
            result.add(s.path());
        }
        state.skippedMtime = skippedMtime;
        if (result.size() > cfg.getScanMaxFiles()) {
            state.truncate("扫描文件数触顶（" + cfg.getScanMaxFiles() + " 个），仅扫描最新的部分文件");
            result = new ArrayList<>(result.subList(0, cfg.getScanMaxFiles()));
        }
        return result;
    }

    /** 逐行扫描单文件：条目切分 → 窗口过滤 → 级别/异常统计；字节/行数预算触顶即停。 */
    private void scanFile(Path file, ToolProperties.Builtin cfg, ScanState state, int totalLinesBudget) {
        String display = file.getFileName().toString();
        long bytesRead = 0;
        long matchedInFile = 0;
        List<String> current = null;
        boolean currentParsed = false;
        LocalDateTime currentTs = null;
        String currentLevel = null;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.totalLines++;
                bytesRead += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                Matcher head = ENTRY_HEAD.matcher(line);
                if (head.find()) {
                    // 收尾上一条目
                    if (current != null) {
                        if (state.accept(currentParsed, currentTs, current, currentLevel)) {
                            matchedInFile++;
                        }
                    }
                    current = new ArrayList<>();
                    current.add(line);
                    currentParsed = true;
                    currentTs = parseTimestamp(head.group(1));
                    currentLevel = head.group(2);
                    if (currentTs == null) {
                        currentParsed = false;
                    }
                } else {
                    if (current == null) {
                        current = new ArrayList<>();
                        currentParsed = false;
                        currentTs = null;
                        currentLevel = null;
                    }
                    current.add(line);
                }
                if (bytesRead > cfg.getScanMaxBytesPerFile()) {
                    state.truncate("文件 " + display + " 读取字节触顶（"
                            + cfg.getScanMaxBytesPerFile() + " 字节），该文件仅统计触顶前内容");
                    break;
                }
                if (state.totalLines >= totalLinesBudget) {
                    state.truncate("累计扫描行数触顶（" + totalLinesBudget + " 行），停止扫描剩余内容");
                    break;
                }
            }
            if (current != null) {
                if (state.accept(currentParsed, currentTs, current, currentLevel)) {
                    matchedInFile++;
                }
            }
        } catch (IOException e) {
            log.warn("读取日志文件失败: {} ({})", file, e.getMessage());
            state.truncate("文件 " + display + " 读取失败: " + e.getMessage());
        }
        state.scannedFiles.add(display + "(窗内 " + matchedInFile + " 条)");
    }

    /** 条目归入统计；返回是否计入窗口。unparsed 条目单列计数（AC-21）。 */
    private boolean acceptEntry(ScanState state, boolean parsed, LocalDateTime ts,
                                List<String> lines, String level) {
        if (!parsed || ts == null) {
            state.unparsedEntries++;
            return false;
        }
        if (ts.isBefore(state.windowStart) || ts.isAfter(state.now.plusSeconds(1))) {
            return false;
        }
        if ("ERROR".equals(level)) {
            state.errorCount++;
        } else if ("WARN".equals(level)) {
            state.warnCount++;
        }
        String joined = String.join("\n", lines);
        Matcher exMatcher = EXCEPTION_CLASS.matcher(joined);
        if (exMatcher.find()) {
            state.exceptionEntries++;
            String className = exMatcher.group();
            int msgLineIdx = firstLineContaining(lines, className);
            String normalized = normalize(lines.get(msgLineIdx));
            state.recordException(className, normalized, ts, lines, msgLineIdx);
        }
        return true;
    }

    private int firstLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return 0;
    }

    /** 消息归一化：UUID → <uuid>，0x 十六进制 → 0x#，数字 → #（消除实例差异，AC-23）。 */
    private String normalize(String line) {
        String out = UUID_PATTERN.matcher(line).replaceAll("<uuid>");
        out = HEX_PATTERN.matcher(out).replaceAll("0x#");
        out = DIGITS_PATTERN.matcher(out).replaceAll("#");
        return out.trim();
    }

    private LocalDateTime parseTimestamp(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d)) {
                return null;
            }
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private String emptyReport(Path root, int minutes, String note) {
        return "## 日志错误分析结果\n"
                + "- 扫描目录: " + root + "\n"
                + "- 时间窗: 最近 " + minutes + " 分钟\n"
                + "- 说明: " + note + "\n"
                + "- 级别计数: ERROR=0, WARN=0, 含异常堆栈=0\n"
                + "- truncated: false\n";
    }

    /** 扫描过程状态与 Markdown 渲染。 */
    private class ScanState {
        final LocalDateTime windowStart;
        final LocalDateTime now;
        int errorCount;
        int warnCount;
        int exceptionEntries;
        int unparsedEntries;
        int totalLines;
        int skippedMtime;
        final List<String> scannedFiles = new ArrayList<>();
        final List<String> truncateReasons = new ArrayList<>();
        final Map<String, ExceptionGroup> groups = new HashMap<>();

        ScanState(LocalDateTime windowStart, LocalDateTime now) {
            this.windowStart = windowStart;
            this.now = now;
        }

        void truncate(String reason) {
            truncateReasons.add(reason);
        }

        boolean accept(boolean parsed, LocalDateTime ts, List<String> lines, String level) {
            return acceptEntry(this, parsed, ts, lines, level);
        }

        void recordException(String className, String normalized, LocalDateTime ts,
                             List<String> entryLines, int msgLineIdx) {
            String key = className + "｜" + normalized;
            ExceptionGroup group = groups.get(key);
            if (group == null) {
                group = new ExceptionGroup(className, normalized, ts);
                // 代表堆栈：异常出现行起 ≤15 行
                int end = Math.min(entryLines.size(), msgLineIdx + MAX_STACK_LINES);
                group.snippet.addAll(entryLines.subList(msgLineIdx, end));
                groups.put(key, group);
            }
            group.count++;
            if (ts.isBefore(group.firstTime)) {
                group.firstTime = ts;
            }
            if (ts.isAfter(group.lastTime)) {
                group.lastTime = ts;
            }
        }

        String renderMarkdown(Path root, int minutes, int topN) {
            StringBuilder sb = new StringBuilder();
            sb.append("## 日志错误分析结果\n");
            sb.append("- 扫描目录: ").append(root).append('\n');
            sb.append("- 时间窗: ").append(windowStart.format(WINDOW_FMT))
                    .append(" ~ ").append(now.format(WINDOW_FMT))
                    .append("（minutes=").append(minutes).append("）\n");
            if (scannedFiles.isEmpty()) {
                sb.append("- 扫描文件: 无（窗内无 mtime 命中的文件");
                if (skippedMtime > 0) {
                    sb.append("；跳过 mtime 过期 ").append(skippedMtime).append(" 个");
                }
                sb.append("）\n");
            } else {
                sb.append("- 扫描文件: ").append(String.join(", ", scannedFiles));
                sb.append("（共 ").append(scannedFiles.size()).append(" 个");
                if (skippedMtime > 0) {
                    sb.append("，跳过 mtime 过期 ").append(skippedMtime).append(" 个");
                }
                sb.append("）\n");
            }
            sb.append("- 级别计数: ERROR=").append(errorCount)
                    .append(", WARN=").append(warnCount)
                    .append(", 含异常堆栈=").append(exceptionEntries).append('\n');
            sb.append("- 无法解析时间戳的条目: ").append(unparsedEntries)
                    .append("（未计入窗口统计）\n");
            sb.append("- truncated: ").append(!truncateReasons.isEmpty()).append('\n');
            for (String reason : truncateReasons) {
                sb.append("  - ").append(reason).append('\n');
            }

            sb.append("\n### 异常分组 Top ").append(Math.min(topN, groups.size())).append('\n');
            if (groups.isEmpty()) {
                sb.append("窗口内未发现异常堆栈。\n");
            } else {
                List<ExceptionGroup> ranked = new ArrayList<>(groups.values());
                ranked.sort(Comparator.comparingInt((ExceptionGroup g) -> g.count).reversed()
                        .thenComparing(g -> g.firstTime));
                int rank = 1;
                for (ExceptionGroup g : ranked.subList(0, Math.min(topN, ranked.size()))) {
                    sb.append(rank++).append(". ").append(g.className)
                            .append("（出现 ").append(g.count).append(" 次，首次 ")
                            .append(g.firstTime.format(TIME_FMT)).append("，末次 ")
                            .append(g.lastTime.format(TIME_FMT)).append("）\n");
                    sb.append("   归一化模式: ").append(g.normalized).append('\n');
                    sb.append("   代表堆栈:\n   ```\n");
                    for (String snippetLine : g.snippet) {
                        sb.append("   ").append(snippetLine).append('\n');
                    }
                    sb.append("   ```\n");
                }
            }
            return sb.toString();
        }
    }

    /** 异常分组聚合体。 */
    private static class ExceptionGroup {
        final String className;
        final String normalized;
        int count;
        LocalDateTime firstTime;
        LocalDateTime lastTime;
        final List<String> snippet = new ArrayList<>();

        ExceptionGroup(String className, String normalized, LocalDateTime ts) {
            this.className = className;
            this.normalized = normalized;
            this.firstTime = ts;
            this.lastTime = ts;
        }
    }
}
