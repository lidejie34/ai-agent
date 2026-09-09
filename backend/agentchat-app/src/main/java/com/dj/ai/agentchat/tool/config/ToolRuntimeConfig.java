package com.dj.ai.agentchat.tool.config;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.callback.ToolCallbackFactory;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.handler.ToolHandlerRouter;
import com.dj.ai.agentchat.tool.spi.BuiltinTool;
import com.dj.ai.agentchat.tool.handler.builtin.BuiltinToolHandler;
import com.dj.ai.agentchat.tool.handler.script.ScriptToolHandler;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import com.dj.ai.agentchat.tool.schema.ToolSchemaInitializer;
import com.dj.ai.agentchat.tool.schema.ToolSchemaStartupRunner;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import com.dj.ai.agentchat.tool.support.DefaultToolSupport;
import com.dj.ai.agentchat.tool.support.ToolSupport;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工具运行时装配（插入迭代 G）：整体受 {@code app.tools.enabled}（默认 true）开关收口。
 *
 * <p>开关关闭时：本配置不生效——{@link MapperScan} 不处理（工具 Mapper 接口不标
 * {@code @Mapper}，MyBatis 自动扫描也不会兜底注册），注册中心/处理器/回调/审计/
 * Schema 初始化器/启动 runner 等 bean 均不装配；ChatService 经
 * {@code ObjectProvider<ToolSupport>} 拿到 null，对话请求不携带 tools 参数
 * （与迭代 F 逐字节等价，AC-63）。管理端 web 层由 {@link ToolAdminWebConfig}
 * 无条件常驻，开关关闭时返回 400 {@code TOOLS_DISABLED}。
 *
 * <p>范式复刻 {@code ChatMemoryConfig}：条件装配 + 独立 MapperScan，
 * 构造型注解不与条件装配混用（各 bean 由本类 {@code @Bean} 显式装配）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.tools", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolProperties.class)
@MapperScan(basePackages = "com.dj.ai.agentchat.tool.mapper")
public class ToolRuntimeConfig {

    /**
     * MyBatis-Plus 分页拦截器（迭代 H 冒烟修复，迭代 G 潜伏缺陷）：未注册时
     * {@code selectPage} 不拼接 LIMIT/COUNT，退化为全量查询且 {@code total=0}，
     * 管理端审计分页失效。注册后对容器内全部 MP mapper 生效。
     */
    @Bean
    public com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor mybatisPlusInterceptor() {
        com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor =
                new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                        com.baomidou.mybatisplus.annotation.DbType.MYSQL));
        return interceptor;
    }

    /**
     * 懒建表器：持 DataSource，首次工具路径经 ScriptUtils 执行 classpath agent-tool-schema.sql。
     */
    @Bean
    public ToolSchemaInitializer toolSchemaInitializer(DataSource dataSource) {
        return new ToolSchemaInitializer(dataSource);
    }

    /**
     * 启动期 best-effort 建表：失败仅 warn 不阻断启动。
     * 工具行不再自动种子，注册表内容完全由管理端维护。
     */
    @Bean
    public ToolSchemaStartupRunner toolSchemaStartupRunner(ToolSchemaInitializer toolSchemaInitializer) {
        return new ToolSchemaStartupRunner(toolSchemaInitializer);
    }

    /**
     * 工具专用执行池（S5）：daemon 固定线程池，工具超时经 Future.get 取消，
     * 不占用/阻塞 Web 容器与 Reactor 线程；容器关闭时随 JVM 退出。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService toolExecutor(ToolProperties properties) {
        int poolSize = Math.max(1, properties.getExecutorPoolSize());
        return Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 密钥脱敏器：内置 ark key / Authorization / api_key 等模式，
     * 追加 app.tools.redact-patterns 增补正则（非法正则 warn 跳过）。
     */
    @Bean
    public SecretRedactor secretRedactor(ToolProperties properties) {
        List<String> extra = properties.getRedactPatterns() == null
                ? List.of() : properties.getRedactPatterns();
        return new SecretRedactor(extra);
    }

    /**
     * BUILTIN 处理器：按 handler_config.bean 路由到容器内全部 BuiltinTool（按 key 归集）。
     */
    @Bean
    public BuiltinToolHandler builtinToolHandler(List<BuiltinTool> builtinTools) {
        return new BuiltinToolHandler(builtinTools);
    }

    /**
     * SCRIPT 白名单脚本执行器（/bin/sh 文件参数 + argv 数组 + 环境净化 + 超时强杀）。
     */
    @Bean
    public ScriptToolHandler scriptToolHandler(ToolProperties properties) {
        return new ScriptToolHandler(properties);
    }

    /**
     * 处理器路由：按 HandlerType 分发；Spring 注入全部 ToolHandler bean
     * （T5 builtin / T6 script 加入后自动归集），无处理器时空列表。
     */
    @Bean
    public ToolHandlerRouter toolHandlerRouter(List<ToolHandler> handlers) {
        return new ToolHandlerRouter(handlers);
    }

    /**
     * 工具调用审计：agent_tool_call_log best-effort 落库。
     */
    @Bean
    public ToolAuditService toolAuditService(AgentToolCallLogMapper agentToolCallLogMapper) {
        return new ToolAuditService(agentToolCallLogMapper);
    }

    /**
     * 回调工厂：把 DB 行装配为 DbToolCallback（坏行抛 SkippableToolException 被注册中心跳过）。
     */
    @Bean
    public ToolCallbackFactory toolCallbackFactory(ToolHandlerRouter toolHandlerRouter,
                                                   ToolAuditService toolAuditService,
                                                   SecretRedactor secretRedactor,
                                                   ExecutorService toolExecutor,
                                                   ToolProperties properties) {
        return new ToolCallbackFactory(toolHandlerRouter, toolAuditService, secretRedactor,
                toolExecutor, properties);
    }

    /**
     * 工具注册中心：volatile 快照 + 懒建表/种子 + 坏行跳过 + DB 故障空集降级 + 自愈；
     * 管理端写操作后调 refresh() 强刷。
     */
    @Bean
    public ToolRegistry toolRegistry(AgentToolMapper agentToolMapper,
                                     ToolCallbackFactory toolCallbackFactory,
                                     ToolSchemaInitializer toolSchemaInitializer) {
        return new ToolRegistry(agentToolMapper, toolCallbackFactory, toolSchemaInitializer);
    }

    /**
     * 对话链路工具挂载：ChatService 经 ObjectProvider 可选注入；
     * 空集/故障返回 null → 零挂载（与迭代 F 逐字节等价）。
     */
    @Bean
    public ToolSupport toolSupport(ToolRegistry toolRegistry,
                                   org.springframework.beans.factory.ObjectProvider<
                                           com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider> mcpToolProvider) {
        // MCP 子开关关闭/未装配时 getIfAvailable()=null → 纯 DB 工具，与迭代 G 逐字节一致（AC-3）
        return new DefaultToolSupport(toolRegistry, mcpToolProvider.getIfAvailable());
    }

    /**
     * 管理端工具服务（T10）：CRUD 校验 + 审计分页 + 写后 refresh。
     * 随工具开关收口——开关关闭时 bean 缺席，常驻的 AdminToolController 经
     * ObjectProvider 拿到 null（拦截器已先挡 400 TOOLS_DISABLED，null 仅防御）。
     */
    @Bean
    public com.dj.ai.agentchat.tool.admin.ToolAdminService toolAdminService(
            AgentToolMapper agentToolMapper,
            AgentToolCallLogMapper agentToolCallLogMapper,
            ToolRegistry toolRegistry,
            List<BuiltinTool> builtinTools,
            ToolProperties properties) {
        return new com.dj.ai.agentchat.tool.admin.ToolAdminService(
                agentToolMapper, agentToolCallLogMapper, toolRegistry, builtinTools, properties);
    }
}
