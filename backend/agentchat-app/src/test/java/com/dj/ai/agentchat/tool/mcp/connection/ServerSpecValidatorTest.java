package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServerSpecValidator 配置校验/filesystem 目录准备单测（迭代4 T2/T6，AC-4/AC-29）。
 */
class ServerSpecValidatorTest {

    private McpProperties.ServerSpec spec(String name, String command, String... args) {
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName(name);
        spec.setCommand(command);
        spec.setArgs(new ArrayList<>(List.of(args)));
        return spec;
    }

    private McpProperties.ServerSpec filesystemSpec(String name, String... dirArgs) {
        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("@modelcontextprotocol/server-filesystem");
        args.addAll(List.of(dirArgs));
        return spec(name, "npx", args.toArray(new String[0]));
    }

    @Test
    void validSpec_noReason() {
        assertThat(ServerSpecValidator.invalidReason(spec("everything", "npx", "-y",
                "@modelcontextprotocol/server-everything"))).isEmpty();
        // 连字符 server 名合法
        assertThat(ServerSpecValidator.invalidReason(spec("my-fs-2", "/opt/homebrew/bin/npx")))
                .isEmpty();
    }

    @Test
    void invalidName_rejected() {
        assertThat(ServerSpecValidator.invalidReason(spec("Bad_Name", "npx"))).isPresent();
        assertThat(ServerSpecValidator.invalidReason(spec("a", "npx"))).isPresent(); // 单字符
        assertThat(ServerSpecValidator.invalidReason(spec("x".repeat(42), "npx"))).isPresent();
        assertThat(ServerSpecValidator.invalidReason(spec("2abc", "npx"))).isPresent(); // 数字开头
        McpProperties.ServerSpec noName = spec("ignored", "npx");
        noName.setName(null);
        assertThat(ServerSpecValidator.invalidReason(noName)).isPresent();
    }

    @Test
    void blankCommand_rejected() {
        assertThat(ServerSpecValidator.invalidReason(spec("ok", "  "))).isPresent();
        McpProperties.ServerSpec noCmd = spec("ok", "x");
        noCmd.setCommand(null);
        assertThat(ServerSpecValidator.invalidReason(noCmd)).isPresent();
    }

    /** AC-4：shell 拼接（字符串形式与 argv 形式）一律拒绝。 */
    @Test
    void shellInvocation_rejected() {
        assertThat(ServerSpecValidator.invalidReason(
                spec("ok", "sh -c \"node srv.js\""))).isPresent();
        assertThat(ServerSpecValidator.invalidReason(
                spec("ok", "sh", "-c", "node srv.js"))).isPresent();
        assertThat(ServerSpecValidator.invalidReason(
                spec("ok", "/bin/bash", "-c", "echo hi"))).isPresent();
        // npx 正常 argv 不受影响
        assertThat(ServerSpecValidator.invalidReason(
                spec("ok", "npx", "-y", "@modelcontextprotocol/server-everything"))).isEmpty();
    }

    /** AC-29：filesystem server 无目录参数 → 非法配置跳过。 */
    @Test
    void filesystemServer_withoutDir_rejected() {
        McpProperties.ServerSpec fs = spec("fs", "npx", "-y",
                "@modelcontextprotocol/server-filesystem");
        assertThat(ServerSpecValidator.isFilesystemServer(fs)).isTrue();
        Optional<String> reason = ServerSpecValidator.invalidReason(fs);
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("工作目录");
    }

    @Test
    void filesystemDirArgs_extractsNonOptionArgsAfterMarker() {
        McpProperties.ServerSpec fs = filesystemSpec("fs", "/data/a", "--verbose", "/data/b");
        assertThat(ServerSpecValidator.filesystemDirArgs(fs)).containsExactly("/data/a", "/data/b");
    }

    /** AC-29：缺失目录尝试创建（成功不告警）。 */
    @Test
    void prepareDirs_missingDir_createdWithoutWarning(@TempDir Path tempDir) {
        Path target = tempDir.resolve("mcp-ws").resolve("sub");
        List<String> warnings = new CopyOnWriteArrayList<>();
        McpProperties.ServerSpec fs = filesystemSpec("fs", target.toString());

        ServerSpecValidator.prepareFilesystemDirs(fs, warnings::add);

        assertThat(Files.isDirectory(target)).isTrue();
        assertThat(warnings).isEmpty();
    }

    /** AC-29：危险路径（$HOME）WARN 不阻断。 */
    @Test
    void prepareDirs_dangerousPath_warnsNonBlocking() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        String home = System.getProperty("user.home");
        McpProperties.ServerSpec fs = filesystemSpec("fs", home);

        ServerSpecValidator.prepareFilesystemDirs(fs, warnings::add);

        assertThat(warnings).anyMatch(w -> w.contains("危险路径"));
    }

    /** 目录无法创建（父路径是文件）→ WARN 不抛错。 */
    @Test
    void prepareDirs_createFailure_warnsNonBlocking(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("afile");
        Files.writeString(file, "x");
        Path target = file.resolve("child"); // 父级是文件，创建必失败
        List<String> warnings = new CopyOnWriteArrayList<>();
        McpProperties.ServerSpec fs = filesystemSpec("fs", target.toString());

        ServerSpecValidator.prepareFilesystemDirs(fs, warnings::add);

        assertThat(warnings).anyMatch(w -> w.contains("创建失败"));
    }

    @Test
    void prepareDirs_nonFilesystemServer_noOp(@TempDir Path tempDir) {
        List<String> warnings = new CopyOnWriteArrayList<>();
        McpProperties.ServerSpec plain = spec("everything", "npx", "-y",
                "@modelcontextprotocol/server-everything");

        ServerSpecValidator.prepareFilesystemDirs(plain, warnings::add);

        assertThat(warnings).isEmpty();
    }
}
