package com.dj.ai.agentchat.tool.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 路径安全工具（插入迭代 G）：日志工具与脚本执行器共用。
 *
 * <p>两道防线：
 * <ol>
 *   <li>字符白名单前置拦截：文件名仅 {@code ^[A-Za-z0-9._-]+$}，{@code ..}、{@code /}、
 *       {@code \} 直接拒（不接受任何路径输入）；</li>
 *   <li>{@code toRealPath()} 后 startsWith 白名单根目录二次校验，防符号链接逃逸。</li>
 * </ol>
 */
public final class PathGuard {

    /** 安全文件名白名单（不含路径分隔符，不含 ..）。 */
    public static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private PathGuard() {
    }

    /** 文件名是否匹配白名单（纯文件名，无路径分隔符）。 */
    public static boolean isSafeFileName(String fileName) {
        return fileName != null && SAFE_FILE_NAME.matcher(fileName).matches();
    }

    /**
     * 把白名单文件名解析到根目录内的真实路径（解析符号链接）。
     *
     * @param rootReal 根目录的 toRealPath 结果（调用方先经 {@link #realRoot} 获取）
     * @param fileName 纯文件名
     * @return 根目录内的真实文件路径
     * @throws IllegalArgumentException 文件名非法或解析后越出根目录
     * @throws java.io.IOException      文件不存在或无法 toRealPath
     */
    public static Path resolveWithin(Path rootReal, String fileName) throws IOException {
        if (!isSafeFileName(fileName)) {
            throw new IllegalArgumentException("文件名非法（仅允许字母/数字/./_/-，不接受路径）: " + fileName);
        }
        Path resolved = rootReal.resolve(fileName);
        // toRealPath 解析全部符号链接；不存在抛 NoSuchFileException（IOException 子类）
        Path real = resolved.toRealPath();
        if (!real.startsWith(rootReal)) {
            throw new IllegalArgumentException("文件越出白名单目录: " + fileName);
        }
        return real;
    }

    /**
     * 解析白名单根目录的真实路径；目录不存在/无法解析返回 empty
     * （调用方据此产出结构化空结果/错误，不抛栈）。
     */
    public static Optional<Path> realRoot(String dir) {
        try {
            Path root = Paths.get(dir).toAbsolutePath().toRealPath();
            return Files.isDirectory(root) ? Optional.of(root) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
