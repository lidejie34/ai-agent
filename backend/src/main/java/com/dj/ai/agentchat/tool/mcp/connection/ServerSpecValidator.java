package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MCP server 配置校验 + filesystem 工作目录准备（插入迭代4，T2/T6，AC-4/AC-29）。
 *
 * <ul>
 *   <li>name 正则 {@code ^[a-z][a-z0-9-]{1,40}$}（工具暴露名前缀，短小写）；</li>
 *   <li>command 非空白（绝对路径建议；argv 数组直传不经 shell，AC-4）；</li>
 *   <li>filesystem 类 server（args 含 {@code server-filesystem} 包标识）必须显式传
 *       至少一个工作目录参数——无目录参数视为非法配置跳过（AC-29）；</li>
 *   <li>目录不存在 → 尝试创建（失败仅告警，不阻断）；危险路径（$HOME / / / 仓库根、
 *       CWD）→ WARN 不硬拦截（部署责任，误伤风险高于收益，AC-29）。</li>
 * </ul>
 */
@Slf4j
public final class ServerSpecValidator {

    /** server 名正则：小写字母开头，小写字母/数字/连字符，总长 2-41。 */
    static final Pattern SERVER_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");

    /** filesystem server 包标识（@modelcontextprotocol/server-filesystem）。 */
    static final String FILESYSTEM_MARKER = "server-filesystem";

    private ServerSpecValidator() {
    }

    /**
     * 纯校验：返回非法原因；合法返回 empty。不做目录创建等副作用。
     */
    public static Optional<String> invalidReason(McpProperties.ServerSpec spec) {
        if (spec == null) {
            return Optional.of("server 配置为空");
        }
        String name = spec.getName();
        if (name == null || !SERVER_NAME.matcher(name.trim()).matches()) {
            return Optional.of("name 非法（需匹配 ^[a-z][a-z0-9-]{1,40}$）: " + name);
        }
        if (spec.getCommand() == null || spec.getCommand().isBlank()) {
            return Optional.of("command 不能为空: server=" + name);
        }
        if (spec.getCommand().contains("sh -c")
                || (spec.getArgs() != null
                        && spec.getArgs().stream().anyMatch(a -> a != null && a.contains("sh -c")))
                || isShellWithDashC(spec)) {
            return Optional.of("禁止 shell 拼接命令（sh/bash -c 或 \"sh -c ...\"，须用 command + args 数组直传 argv，AC-4）: server=" + name);
        }
        if (isFilesystemServer(spec)) {
            List<String> dirs = filesystemDirArgs(spec);
            if (dirs.isEmpty()) {
                return Optional.of("filesystem server 未配置工作目录参数（AC-29）: server=" + name);
            }
        }
        return Optional.empty();
    }

    /**
     * shell 拼接判定：command basename 为 sh/bash/zsh/fish 且 args 含 "-c"
     * （argv 形式的 shell 拼接，与 "sh -c ..." 字符串同等禁止，AC-4）。
     */
    private static boolean isShellWithDashC(McpProperties.ServerSpec spec) {
        String command = spec.getCommand();
        if (command == null || spec.getArgs() == null) {
            return false;
        }
        String basename = command;
        int slash = command.lastIndexOf('/');
        if (slash >= 0 && slash < command.length() - 1) {
            basename = command.substring(slash + 1);
        }
        boolean shell = basename.equals("sh") || basename.equals("bash")
                || basename.equals("zsh") || basename.equals("fish");
        return shell && spec.getArgs().stream().anyMatch("-c"::equals);
    }

    /**
     * filesystem 类 server 判定：args 含包标识（包名或本地路径皆按子串匹配）。
     */
    static boolean isFilesystemServer(McpProperties.ServerSpec spec) {
        return spec.getArgs() != null
                && spec.getArgs().stream().anyMatch(a -> a != null && a.contains(FILESYSTEM_MARKER));
    }

    /**
     * 提取 filesystem 工作目录参数：包标识参数之后、非选项（不以 - 开头）的参数。
     */
    static List<String> filesystemDirArgs(McpProperties.ServerSpec spec) {
        List<String> args = spec.getArgs() == null ? List.of() : spec.getArgs();
        int markerIdx = -1;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a != null && a.contains(FILESYSTEM_MARKER)) {
                markerIdx = i;
                break;
            }
        }
        if (markerIdx < 0) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>();
        for (int i = markerIdx + 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null || a.isBlank() || a.startsWith("-")) {
                continue;
            }
            dirs.add(a.trim());
        }
        return dirs;
    }

    /**
     * 启动期目录准备：危险路径告警（不阻断）；缺失目录尝试创建（失败告警）。
     * 非 filesystem server 无操作。
     *
     * @param warn 告警出口（管理器内接脱敏日志）
     */
    public static void prepareFilesystemDirs(McpProperties.ServerSpec spec, java.util.function.Consumer<String> warn) {
        if (!isFilesystemServer(spec)) {
            return;
        }
        for (String dir : filesystemDirArgs(spec)) {
            Path path = Paths.get(dir);
            String normalized = path.toAbsolutePath().normalize().toString();
            if (isDangerous(normalized)) {
                warn.accept("filesystem 工作目录配置为危险路径（建议专用目录，勿用家目录/根/仓库根）: "
                        + normalized + "（server=" + spec.getName() + "）");
            }
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                    log.info("已创建 MCP filesystem 工作目录: {}", normalized);
                } catch (IOException | SecurityException e) {
                    warn.accept("filesystem 工作目录不存在且创建失败: " + normalized
                            + "（server=" + spec.getName() + "），原因: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 危险路径判定：$HOME、文件系统根 /、当前工作目录（mvn 时为 backend/）及其父目录（仓库根）。
     */
    static boolean isDangerous(String normalizedAbsolutePath) {
        String home = System.getProperty("user.home");
        String cwd = Paths.get("").toAbsolutePath().normalize().toString();
        String cwdParent = Paths.get(cwd).getParent() != null
                ? Paths.get(cwd).getParent().normalize().toString() : null;
        if (home != null && normalizedAbsolutePath.equals(Paths.get(home).toAbsolutePath().normalize().toString())) {
            return true;
        }
        if ("/".equals(normalizedAbsolutePath)) {
            return true;
        }
        if (normalizedAbsolutePath.equals(cwd)) {
            return true;
        }
        return cwdParent != null && normalizedAbsolutePath.equals(cwdParent);
    }
}
