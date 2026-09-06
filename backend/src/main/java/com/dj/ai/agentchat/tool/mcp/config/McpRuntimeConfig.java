package com.dj.ai.agentchat.tool.mcp.config;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.mcp.admin.AdminMcpService;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolCallbackFactory;
import com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider;
import com.dj.ai.agentchat.tool.mcp.connection.McpClientFactory;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import com.dj.ai.agentchat.tool.mcp.connection.StdioMcpClientFactory;
import com.dj.ai.agentchat.tool.mcp.migrate.AuditColumnWidthMigration;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;

/**
 * MCP 工具运行时装配（插入迭代4，路径 B：mcp-core 0.14.0 手工装配，不引
 * spring-ai-starter-mcp-client，无任何 MCP 自动配置——触达模型的 ToolCallback
 * 全部由本工程 McpToolCallback 产生，AC-16 结构性保证）。
 *
 * <p><b>双开关条件装配</b>：
 * <ul>
 *   <li>类级：{@code app.tools.enabled}（matchIfMissing=true，总开关，AC-1）——
 *       总开关关闭时本配置整包不生效；</li>
 *   <li>bean 级：{@code app.tools.mcp.enabled}（matchIfMissing=true，子开关，AC-2）——
 *       子开关关闭时 MCP 连接管理器/工厂/回调/provider/admin service 均不装配，
 *       {@code DefaultToolSupport} 经 {@code ObjectProvider<McpToolProvider>} 拿到
 *       null → 纯 DB 工具行为，与迭代 G 逐字节一致（AC-3）。</li>
 * </ul>
 *
 * <p>管理端 web 层（{@code McpAdminController}）随组件扫描常驻，MCP bean 缺失时
 * 返回空列表而非 500（AC-35）；鉴权复用常驻 {@code AdminAuthInterceptor}（/api/admin/**）。
 *
 * <p>本配置不标 {@code @MapperScan}：MCP 无新 Mapper（审计复用 G 的
 * AgentToolCallLogMapper），避免条件装配语义被组件扫描干扰。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.tools", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class McpRuntimeConfig {

    // bean 定义随 T2（连接管理层）/T3（包装层）/T5（管理端）逐步加入；
    // 每个 @Bean 方法另标 @ConditionalOnProperty(prefix="app.tools.mcp", name="enabled",
    // havingValue="true", matchIfMissing=true) 收口子开关。

    /**
     * T1：审计列宽幂等迁移（ApplicationRunner，best-effort）。MCP 子开关关闭时不装配——
     * 不会有 MCP 长工具名产生，无迁移必要。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AuditColumnWidthMigration auditColumnWidthMigration(DataSource dataSource) {
        return new AuditColumnWidthMigration(dataSource);
    }

    /**
     * T2：stdio MCP 连接工厂（全工程唯一 SDK 装配点：ServerParameters/StdioClientTransport/
     * McpClient.sync/initialize 握手；命令 argv 直传不经 shell，AC-4）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpClientFactory mcpClientFactory() {
        return new StdioMcpClientFactory();
    }

    /**
     * T2 壳 / T3 实现：MCP 工具回调工厂（READY 连接的发现工具 → McpToolCallback）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpToolCallbackFactory mcpToolCallbackFactory(ToolAuditService toolAuditService,
                                                        SecretRedactor secretRedactor,
                                                        ExecutorService toolExecutor,
                                                        ToolProperties properties) {
        return new McpToolCallbackFactory(toolAuditService, secretRedactor, toolExecutor, properties);
    }

    /**
     * T2：MCP 连接管理器（ApplicationRunner：启动并行连接/发现/失败隔离；
     * 运行期崩溃 markUnavailable 不重启；@PreDestroy 全关闭，AC-7~AC-11）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpServerConnectionManager mcpServerConnectionManager(ToolProperties properties,
                                                                 McpClientFactory mcpClientFactory,
                                                                 SecretRedactor secretRedactor,
                                                                 McpToolCallbackFactory mcpToolCallbackFactory) {
        return new McpServerConnectionManager(properties, mcpClientFactory, secretRedactor,
                mcpToolCallbackFactory);
    }

    /**
     * T4：MCP 回调供给（非 starter 自动注册；DefaultToolSupport 经 ObjectProvider
     * 可选注入，缺席时纯 DB 行为与 G 逐字节一致，AC-3/AC-16）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpToolProvider mcpToolProvider(McpServerConnectionManager mcpServerConnectionManager) {
        return new McpToolProvider(mcpServerConnectionManager);
    }

    /**
     * T5：MCP 管理端只读服务（控制器常驻；缺席时 GET /api/admin/mcp/servers 返回空，AC-35）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.tools.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AdminMcpService adminMcpService(McpServerConnectionManager mcpServerConnectionManager) {
        return new AdminMcpService(mcpServerConnectionManager);
    }
}
