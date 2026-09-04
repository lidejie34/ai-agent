package com.dj.ai.agentchat.tool.handler.script;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.security.PathGuard;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * SCRIPT 白名单脚本执行器（插入迭代 G，AC-27~33、53、57）。
 *
 * <p>安全约定（详见 full_tech_plan 8.2）：
 * <ul>
 *   <li><b>目录逃逸防护</b>：脚本文件名只来自 DB（handler_config.script），白名单正则 +
 *       toRealPath startsWith 双重校验；符号链接指向目录外即拒；</li>
 *   <li><b>无 shell 注入面</b>：{@code /bin/sh <scriptFile> argv...}——脚本作为 sh 的文件参数
 *       （绝非 {@code sh -c} 字符串），模型数据全部是独立 argv 元素，{@code ;|$()} 等元字符无语义；
 *       argv 仅按 input_schema 声明的属性名生成具名参数 {@code --name value}
 *       （≤10 个参数、单值 ≤200 字符，未声明键忽略并 WARN）；</li>
 *   <li><b>环境净化</b>：environment().clear() 后仅放白名单 PATH/LANG/LOG_DIR/TOOL_OUTPUT_MAX_CHARS，
 *       不继承 ARK_API_KEY/DB 口令；工作目录锁定脚本目录；</li>
 *   <li><b>超时强杀</b>：waitFor 超时 → destroy → 3s 宽限 → destroyForcibly，结果 TIMEOUT；</li>
 *   <li><b>输出截断</b>：stdout/stderr 各有字节采集上限与字符截断标记；非零退出 → SCRIPT_EXIT_NONZERO。</li>
 * </ul>
 */
@Slf4j
public class ScriptToolHandler implements ToolHandler {

    /** 单次调用最多透传的具名参数个数。 */
    static final int MAX_PARAMS = 10;
    /** 单个参数值最大字符数。 */
    static final int MAX_PARAM_VALUE_CHARS = 200;
    /** 超时后 destroy 的宽限等待（毫秒）。 */
    private static final long KILL_GRACE_MS = 3000;
    /** 非零退出时 error message 携带的 stderr 尾部长度。 */
    private static final int ERROR_STDERR_TAIL_CHARS = 500;

    private final ToolProperties properties;

    public ScriptToolHandler(ToolProperties properties) {
        this.properties = properties;
    }

    @Override
    public HandlerType type() {
        return HandlerType.SCRIPT;
    }

    @Override
    public void validateConfig(String handlerConfigJson) {
        // 装载/管理端写入时校验：脚本名合法 + 文件真实存在于白名单目录内（坏行跳过/400）
        resolveScript(handlerConfigJson);
    }

    @Override
    public ToolExecutionResult execute(AgentToolPO tool, Map<String, Object> args,
                                       ToolExecutionContext ctx) {
        Path script;
        try {
            script = resolveScript(tool.getHandlerConfig());
        } catch (InvalidChatRequestException e) {
            log.warn("SCRIPT 工具运行期脚本定位失败: toolName={}, {}", tool.getToolName(), e.getMessage());
            return ToolExecutionResult.failed("SCRIPT_NOT_FOUND", e.getMessage());
        }

        List<String> argv;
        try {
            argv = buildArgv(tool, args);
        } catch (InvalidChatRequestException e) {
            return ToolExecutionResult.failed("INVALID_ARGS", e.getMessage());
        }

        Path root = script.getParent();
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add(script.toString());
        command.addAll(argv);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", "/usr/bin:/bin");
        env.put("LANG", "en_US.UTF-8");
        env.put("LOG_DIR", Paths.get(properties.getBuiltin().getLogDir()).toAbsolutePath().toString());
        env.put("TOOL_OUTPUT_MAX_CHARS", String.valueOf(ctx.outputMaxChars()));

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // /bin/sh 不存在等环境问题（Windows 非目标）
            return ToolExecutionResult.failed("SCRIPT_LAUNCH_FAILED", "脚本启动失败: " + e.getMessage());
        }

        long byteCap = (long) ctx.outputMaxChars() * 3L;
        StreamGobbler out = new StreamGobbler(process.getInputStream(), byteCap, "script-stdout");
        StreamGobbler err = new StreamGobbler(process.getErrorStream(), byteCap, "script-stderr");
        out.start();
        err.start();

        boolean finished;
        try {
            finished = process.waitFor(ctx.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return ToolExecutionResult.timeout("脚本执行被中断");
        }
        if (!finished) {
            log.warn("脚本执行超时，已销毁: toolName={}, timeoutMs={}", tool.getToolName(), ctx.timeoutMs());
            process.destroy();
            try {
                if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            joinQuietly(out, err);
            return ToolExecutionResult.timeout("脚本执行超时（" + ctx.timeoutMs() + "ms），已终止: "
                    + script.getFileName());
        }

        joinQuietly(out, err);
        long duration = System.currentTimeMillis() - start;
        int exitCode = process.exitValue();
        String stdout = renderOutput(out, ctx.outputMaxChars(), "stdout");
        String stderr = renderOutput(err, ctx.outputMaxChars(), "stderr");

        if (exitCode == 0) {
            String text = stdout;
            if (!stderr.isBlank()) {
                text += "\n[stderr]\n" + tail(stderr, ERROR_STDERR_TAIL_CHARS);
            }
            return ToolExecutionResult.success(text);
        }
        String message = "脚本退出码 " + exitCode + "（耗时 " + duration + "ms）: "
                + tail(stderr, ERROR_STDERR_TAIL_CHARS);
        return ToolExecutionResult.failed("SCRIPT_EXIT_NONZERO", message, stdout);
    }

    /**
     * 按 input_schema 声明属性生成 {@code --name value} 具名参数；
     * 未声明键忽略并 WARN；参数个数/单值长度超限 → InvalidChatRequestException。
     */
    private List<String> buildArgv(AgentToolPO tool, Map<String, Object> args) {
        List<String> argv = new ArrayList<>();
        if (args == null || args.isEmpty()) {
            return argv;
        }
        List<String> declared = declaredParamNames(tool.getInputSchema());
        if (declared.isEmpty()) {
            log.warn("SCRIPT 工具 input_schema 未声明属性，模型入参全部忽略: toolName={}", tool.getToolName());
            return argv;
        }
        for (String key : args.keySet()) {
            if (!declared.contains(key)) {
                log.warn("SCRIPT 工具收到 schema 未声明的参数，已忽略: toolName={}, param={}",
                        tool.getToolName(), key);
                continue;
            }
        }
        for (String name : declared) {
            if (!args.containsKey(name) || args.get(name) == null) {
                continue;
            }
            if (argv.size() / 2 >= MAX_PARAMS) {
                throw new InvalidChatRequestException("脚本参数个数超过上限 " + MAX_PARAMS);
            }
            String value = String.valueOf(args.get(name));
            if (value.length() > MAX_PARAM_VALUE_CHARS) {
                throw new InvalidChatRequestException(
                        "脚本参数 " + name + " 值长度超过上限 " + MAX_PARAM_VALUE_CHARS + " 字符");
            }
            argv.add("--" + name);
            argv.add(value);
        }
        return argv;
    }

    /** 解析 input_schema 的 properties 键（fastjson2 保持声明顺序）；非法/缺失返回空列表。 */
    private List<String> declaredParamNames(String inputSchemaJson) {
        try {
            JSONObject schema = JSON.parseObject(inputSchemaJson);
            JSONObject props = schema == null ? null : schema.getJSONObject("properties");
            if (props == null) {
                return List.of();
            }
            return new ArrayList<>(props.keySet());
        } catch (Exception e) {
            log.warn("SCRIPT 工具 input_schema 解析失败，按无参数处理: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 handler_config.script 并做白名单/逃逸/存在性校验；
     * 失败抛 {@link InvalidChatRequestException}（装载期坏行跳过 / 管理端 400 / 运行期结构化失败）。
     */
    private Path resolveScript(String handlerConfigJson) {
        JSONObject config;
        try {
            config = JSON.parseObject(handlerConfigJson);
        } catch (Exception e) {
            throw new InvalidChatRequestException("SCRIPT 工具 handler_config 非法 JSON: " + e.getMessage());
        }
        if (config == null) {
            throw new InvalidChatRequestException("SCRIPT 工具 handler_config 不是 JSON 对象");
        }
        String scriptName = config.getString("script");
        if (scriptName == null || scriptName.isBlank()) {
            throw new InvalidChatRequestException("SCRIPT 工具 handler_config 缺少非空 script 文件名");
        }
        scriptName = scriptName.trim();
        if (!PathGuard.isSafeFileName(scriptName)) {
            throw new InvalidChatRequestException("脚本文件名非法（仅允许字母/数字/点/下划线/连字符）: " + scriptName);
        }
        Optional<Path> rootOpt = PathGuard.realRoot(properties.getScriptDir());
        if (rootOpt.isEmpty()) {
            throw new InvalidChatRequestException(
                    "脚本白名单目录不存在或不可读: " + properties.getScriptDir());
        }
        Path root = rootOpt.get();
        try {
            Path resolved = PathGuard.resolveWithin(root, scriptName);
            if (!Files.isRegularFile(resolved)) {
                throw new InvalidChatRequestException("脚本不是常规文件: " + scriptName);
            }
            return resolved;
        } catch (IllegalArgumentException e) {
            // 文件名非法 / 越出白名单目录
            throw new InvalidChatRequestException(e.getMessage());
        } catch (IOException e) {
            throw new InvalidChatRequestException("脚本文件不存在或不可读: " + scriptName);
        }
    }

    private String renderOutput(StreamGobbler gobbler, int maxChars, String label) {
        String raw = gobbler.text();
        if (raw.length() <= maxChars && !gobbler.isTruncated()) {
            return raw;
        }
        String head = raw.length() > maxChars ? raw.substring(0, maxChars) : raw;
        return head + "\n...[" + label + " truncated]（原始长度约 " + gobbler.totalBytes() + " 字节）";
    }

    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(text.length() - maxChars);
    }

    private static void joinQuietly(StreamGobbler... gobblers) {
        for (StreamGobbler g : gobblers) {
            try {
                g.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 进程输出采集线程：持续排空管道（防止输出撑满管道缓冲区卡死进程），
     * 超过字节预算后只排空不追加，置 truncated 标记。
     */
    private static class StreamGobbler extends Thread {
        private final InputStream in;
        private final long byteCap;
        private final StringBuilder sb = new StringBuilder();
        private volatile long totalBytes;
        private volatile boolean truncated;

        StreamGobbler(InputStream in, long byteCap, String name) {
            super(name);
            this.in = in;
            this.byteCap = byteCap;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    totalBytes += n;
                    if (sb.length() < byteCap) {
                        sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                // 进程销毁等场景管道关闭：正常收尾
            }
        }

        String text() {
            return sb.toString();
        }

        long totalBytes() {
            return totalBytes;
        }

        boolean isTruncated() {
            return truncated;
        }
    }
}
