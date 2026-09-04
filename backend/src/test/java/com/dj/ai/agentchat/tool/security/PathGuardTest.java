package com.dj.ai.agentchat.tool.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3：PathGuard 两道防线（字符白名单 + realpath startsWith），日志工具与脚本执行器共用。
 */
class PathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void realRoot_existingDir_returnsRealPath() {
        assertThat(PathGuard.realRoot(tempDir.toString())).isPresent();
    }

    @Test
    void realRoot_missingDir_returnsEmpty() {
        assertThat(PathGuard.realRoot(tempDir.resolve("no-such-dir").toString())).isEmpty();
    }

    @Test
    void resolveWithin_safeFileName_resolves() throws IOException {
        Path logFile = tempDir.resolve("app.log");
        Files.writeString(logFile, "hello");

        Path resolved = PathGuard.resolveWithin(tempDir.toRealPath(), "app.log");

        assertThat(resolved).exists();
        assertThat(resolved.getFileName().toString()).isEqualTo("app.log");
    }

    @Test
    void resolveWithin_parentTraversal_rejected() {
        assertThatThrownBy(() -> PathGuard.resolveWithin(tempDir.toRealPath(), "../escape.log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名非法");
    }

    @Test
    void resolveWithin_absolutePath_rejected() {
        assertThatThrownBy(() -> PathGuard.resolveWithin(tempDir.toRealPath(), "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveWithin_backslash_rejected() {
        assertThatThrownBy(() -> PathGuard.resolveWithin(tempDir.toRealPath(), "..\\evil.log"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveWithin_missingFile_throwsIoException() {
        assertThatThrownBy(() -> PathGuard.resolveWithin(tempDir.toRealPath(), "missing.log"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void resolveWithin_symlinkEscapingRoot_rejected() throws IOException {
        // 根目录外的真实文件 + 根目录内指向它的符号链接：toRealPath 后越出根目录 → 拒绝
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);
        Path outsideFile = outsideDir.resolve("secret.log");
        Files.writeString(outsideFile, "secret");
        Path rootDir = tempDir.resolve("root");
        Files.createDirectories(rootDir);
        Path link = rootDir.resolve("link.log");
        Files.createSymbolicLink(link, outsideFile);

        assertThatThrownBy(() -> PathGuard.resolveWithin(rootDir.toRealPath(), "link.log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越出白名单目录");
    }

    @Test
    void safeName_acceptsCommonLogFileNames() {
        assertThat(PathGuard.isSafeFileName("application.log")).isTrue();
        assertThat(PathGuard.isSafeFileName("app.2026-09-04.log")).isTrue();
        assertThat(PathGuard.isSafeFileName("log_error_count.sh")).isTrue();
        assertThat(PathGuard.isSafeFileName("../x.log")).isFalse();
        assertThat(PathGuard.isSafeFileName("a/b.log")).isFalse();
        assertThat(PathGuard.isSafeFileName(null)).isFalse();
    }
}
