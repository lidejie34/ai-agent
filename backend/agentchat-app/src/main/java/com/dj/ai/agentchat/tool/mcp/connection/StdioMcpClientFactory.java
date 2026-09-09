package com.dj.ai.agentchat.tool.mcp.connection;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 mcp-core 0.14.0 的 stdio 连接工厂（插入迭代4，T2）——<b>全工程唯一 SDK 装配处</b>：
 * {@link ServerParameters}（command 绝对路径 + args 数组直传 argv，不经 shell，AC-4；
 * env 显式补 PATH/HOME 后叠加配置 env）→ {@link StdioClientTransport}（Jackson JSON mapper）
 * → {@link McpClient#sync} 构建并 {@code initialize()} 握手。
 *
 * <p>两点 SDK/离线适配（sources jar 实证）：
 * <ul>
 *   <li>显式传入 no-op {@link JsonSchemaValidator}：离线库无 networknt json-schema-validator
 *       1.5.7 jar（pom 已排除其传递），默认 ServiceLoader 实现会因缺类初始化失败；
 *       structuredContent 输出校验本迭代不用（只取 TextContent 文本）；</li>
 *   <li>握手失败（命令不存在/启动即退/初始化超时）抛 {@link McpConnectException}，
 *       并 closeGracefully 回收半启动子进程（R7）。</li>
 * </ul>
 */
@Slf4j
public class StdioMcpClientFactory implements McpClientFactory {

    private static final String CLIENT_NAME = "dj-agent-chat-mcp-client";
    private static final String CLIENT_VERSION = "1.0.0";

    /**
     * no-op 输出 schema 校验：始终判 valid（不使用 structuredContent，绕开缺失的 networknt 依赖）。
     */
    private static final JsonSchemaValidator NOOP_SCHEMA_VALIDATOR =
            (schema, structuredContent) -> JsonSchemaValidator.ValidationResponse.asValid(null);

    @Override
    public McpClientGateway connect(McpProperties.ServerSpec spec, Duration requestTimeout,
                                    Duration initTimeout) {
        ServerParameters params = ServerParameters.builder(spec.getCommand())
                .args(spec.getArgs() == null ? List.of() : List.copyOf(spec.getArgs()))
                .env(buildEnv(spec))
                .build();
        StdioClientTransport transport = new StdioClientTransport(params,
                new JacksonMcpJsonMapperSupplier().get());
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .requestTimeout(requestTimeout)
                .initializationTimeout(initTimeout)
                .jsonSchemaValidator(NOOP_SCHEMA_VALIDATOR)
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            // 半启动回收：transport 可能已起子进程（R7/AC-9）
            closeQuietly(client);
            throw new McpConnectException(
                    "MCP server 握手失败: " + spec.getName() + ": " + safeMessage(e), e);
        }
        return new StdioMcpClientGateway(spec.getName(), client, transport);
    }

    /**
     * 子进程环境：ServerParameters 已默认继承父进程白名单环境（HOME/PATH/SHELL/USER/...），
     * 此处显式补 PATH/HOME（npx/node 依赖），再叠加配置声明 env（视为敏感，文档警示 R9）。
     */
    private Map<String, String> buildEnv(McpProperties.ServerSpec spec) {
        Map<String, String> env = new HashMap<>();
        putIfNotBlank(env, "PATH", System.getenv("PATH"));
        putIfNotBlank(env, "HOME", System.getenv("HOME"));
        if (spec.getEnv() != null) {
            env.putAll(spec.getEnv());
        }
        return env;
    }

    private static void putIfNotBlank(Map<String, String> env, String key, String value) {
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Throwable ignored) {
            // 握手失败路径的尽力回收
        }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }
}
