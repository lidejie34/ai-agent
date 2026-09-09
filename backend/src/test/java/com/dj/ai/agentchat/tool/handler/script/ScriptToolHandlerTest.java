package com.dj.ai.agentchat.tool.handler.script;

import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6：ScriptToolHandler 白名单脚本闭环（AC-27~33、53、57）——临时白名单目录 +
 * 夹具内生成极简脚本，全部离线（macOS/Linux /bin/sh）。
 */
class ScriptToolHandlerTest {

    @TempDir
    Path tempDir;

    private Path scriptDir;
    private Path logDir;
    private ToolProperties properties;
    private ScriptToolHandler handler;

    private static final String SCHEMA_MINUTES =
            "{\"type\":\"object\",\"properties\":{\"minutes\":{\"type\":\"integer\"}}}";

    @BeforeEach
    void setUp() throws IOException {
        scriptDir = Files.createDirectories(tempDir.resolve("scripts"));
        logDir = Files.createDirectories(tempDir.resolve("logs"));
        properties = new ToolProperties();
        properties.setScriptDir(scriptDir.toString());
        properties.getBuiltin().setLogDir(logDir.toString());
        handler = new ScriptToolHandler(properties);
    }

    private String writeScript(String name, String body) throws IOException {
        Path p = scriptDir.resolve(name);
        Files.writeString(p, body);
        return name;
    }

    private AgentToolPO tool(String scriptName, String schema) {
        AgentToolPO po = new AgentToolPO();
        po.setId(1L);
        po.setToolName("demo_script_tool");
        po.setHandlerType("SCRIPT");
        po.setHandlerConfig("{\"script\":\"" + scriptName + "\"}");
        po.setInputSchema(schema);
        return po;
    }

    private ToolExecutionContext ctx(long timeoutMs, int outputMaxChars) {
        return new ToolExecutionContext("sess-1", "req-1", timeoutMs, outputMaxChars);
    }

    private ToolExecutionResult run(String script, String schema, Map<String, Object> args) {
        return handler.execute(tool(script, schema), args, ctx(10_000, 8000));
    }

    // ---------- 装载期校验（AC-27） ----------

    @Test
    void validateConfig_happyPath_passes() throws IOException {
        String name = writeScript("ok.sh", "#!/bin/sh\necho ok\n");
        handler.validateConfig("{\"script\":\"" + name + "\"}");
    }

    @Test
    void validateConfig_missingScriptKey_throws() {
        assertThatThrownBy(() -> handler.validateConfig("{\"other\":1}"))
                .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> handler.validateConfig("not-json"))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void validateConfig_unsafeFileName_throws() throws IOException {
        writeScript("ok.sh", "echo ok\n");
        assertThatThrownBy(() -> handler.validateConfig("{\"script\":\"../ok.sh\"}"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("文件名");
    }

    @Test
    void validateConfig_missingScriptFile_throws() {
        assertThatThrownBy(() -> handler.validateConfig("{\"script\":\"ghost.sh\"}"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void validateConfig_missingScriptDir_throws() {
        properties.setScriptDir(tempDir.resolve("no-such-scripts").toString());
        assertThatThrownBy(() -> handler.validateConfig("{\"script\":\"ok.sh\"}"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("白名单目录");
    }

    @Test
    void validateConfig_symlinkEscape_throws() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside.sh"), "echo outside\n");
        Files.createSymbolicLink(scriptDir.resolve("evil.sh"), outside);

        assertThatThrownBy(() -> handler.validateConfig("{\"script\":\"evil.sh\"}"))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void runtime_missingScript_returnsStructuredFailure() {
        // 装载后脚本被删：运行期 SCRIPT_NOT_FOUND，不抛异常
        ToolExecutionResult r = handler.execute(tool("ghost.sh", SCHEMA_MINUTES),
                Map.of("minutes", 30), ctx(10_000, 8000));
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("SCRIPT_NOT_FOUND");
    }

    // ---------- argv 数组与注入防护（AC-28/32） ----------

    @Test
    void args_becomeIndependentNamedArgv() throws IOException {
        String name = writeScript("echo_args.sh",
                "#!/bin/sh\necho \"COUNT=$#\"\nfor a in \"$@\"; do echo \"ARG=$a\"; done\n");

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, Map.of("minutes", 15));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("COUNT=2").contains("ARG=--minutes").contains("ARG=15");
    }

    @Test
    void undeclaredParams_areIgnored() throws IOException {
        String name = writeScript("echo_args.sh",
                "#!/bin/sh\necho \"COUNT=$#\"\nfor a in \"$@\"; do echo \"ARG=$a\"; done\n");
        Map<String, Object> args = new HashMap<>();
        args.put("minutes", 5);
        args.put("evil", "ignored");

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, args);

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("COUNT=2").doesNotContain("ARG=--evil");
    }

    @Test
    void shellMetachars_areLiteral_noInjection() throws IOException {
        String name = writeScript("echo_args.sh",
                "#!/bin/sh\necho \"COUNT=$#\"\nfor a in \"$@\"; do echo \"ARG=$a\"; done\n");
        String schema = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";
        String malicious = "x; echo INJECTED_LINE; $(id) `id`";

        ToolExecutionResult r = run(name, schema, Map.of("q", malicious));

        assertThat(r.ok()).isTrue();
        // 命令替换/管道未执行：输出中没有 uid=（id 命令产物），恶意串仅作为字面 argv 回显
        assertThat(r.text()).doesNotContain("uid=");
        assertThat(r.text()).contains("$(id)");
    }

    @Test
    void tooManyParams_invalidArgs() throws IOException {
        String name = writeScript("echo_args.sh", "#!/bin/sh\necho ok\n");
        StringBuilder props = new StringBuilder();
        Map<String, Object> args = new HashMap<>();
        for (int i = 1; i <= 11; i++) {
            if (i > 1) {
                props.append(',');
            }
            props.append("\"p").append(i).append("\":{\"type\":\"string\"}");
            args.put("p" + i, "v");
        }
        String schema = "{\"type\":\"object\",\"properties\":{" + props + "}}";

        ToolExecutionResult r = run(name, schema, args);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("INVALID_ARGS");
    }

    @Test
    void overlyLongParamValue_invalidArgs() throws IOException {
        String name = writeScript("echo_args.sh", "#!/bin/sh\necho ok\n");
        String longValue = "v".repeat(201);

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, Map.of("minutes", longValue));

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("INVALID_ARGS");
    }

    // ---------- 工作目录与环境净化（AC-29） ----------

    @Test
    void workingDir_isScriptDir() throws IOException {
        String name = writeScript("where.sh", "#!/bin/sh\npwd\n");

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, Map.of());

        assertThat(r.ok()).isTrue();
        assertThat(r.text().trim()).isEqualTo(scriptDir.toRealPath().toString());
    }

    @Test
    void env_isSanitized_andWhitelistInjected() throws IOException {
        String name = writeScript("env.sh",
                "#!/bin/sh\necho \"PATH=$PATH\"\necho \"LOG_DIR=$LOG_DIR\"\necho \"KEY=${ARK_API_KEY:-none}\"\n");

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, Map.of());

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("PATH=/usr/bin:/bin");
        assertThat(r.text()).contains("LOG_DIR=" + logDir.toAbsolutePath());
        assertThat(r.text()).contains("KEY=none");
    }

    // ---------- 超时强杀（AC-30） ----------

    @Test
    void timeout_killsProcess_andReturnsTimeout() throws IOException {
        String name = writeScript("slow.sh", "#!/bin/sh\nsleep 5\necho should-not-appear\n");

        long start = System.currentTimeMillis();
        ToolExecutionResult r = handler.execute(tool(name, SCHEMA_MINUTES),
                Map.of(), ctx(500, 8000));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ToolExecutionResult.STATUS_TIMEOUT);
        assertThat(elapsed).isLessThan(3000);
        assertThat(r.errorMessage()).contains("超时");
    }

    // ---------- 输出截断与退出码（AC-31） ----------

    @Test
    void hugeOutput_isTruncatedWithMarker() throws IOException {
        String name = writeScript("big.sh",
                "#!/bin/sh\ni=0; while [ $i -lt 1000 ]; do echo \"line $i padding-padding-padding\"; i=$((i+1)); done\n");

        ToolExecutionResult r = handler.execute(tool(name, SCHEMA_MINUTES),
                Map.of(), ctx(10_000, 200));

        assertThat(r.ok()).isTrue();
        assertThat(r.text()).contains("[stdout truncated]");
    }

    @Test
    void nonZeroExit_failsWithStderrTail() throws IOException {
        String name = writeScript("fail.sh",
                "#!/bin/sh\necho partial-out\necho boom-error 1>&2\nexit 3\n");

        ToolExecutionResult r = run(name, SCHEMA_MINUTES, Map.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("SCRIPT_EXIT_NONZERO");
        assertThat(r.errorMessage()).contains("boom-error").contains("3");
        assertThat(r.text()).contains("partial-out");
    }

}
