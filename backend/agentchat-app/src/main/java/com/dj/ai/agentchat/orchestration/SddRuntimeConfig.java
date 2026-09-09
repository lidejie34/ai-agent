package com.dj.ai.agentchat.orchestration;

import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationSchemaInitializer;
import com.dj.ai.agentchat.orchestration.audit.mapper.OrchestrationRunMapper;
import com.dj.ai.agentchat.orchestration.executor.ExecutorClient;
import com.dj.ai.agentchat.orchestration.planner.PlannerClient;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * SDD 编排运行时装配（迭代5）：整体受 {@code app.sdd.enabled}（默认 false）开关收口。
 *
 * <p>范式复刻 {@code ToolRuntimeConfig}/{@code ChatMemoryConfig}：条件装配 +
 * 独立 MapperScan，构造型注解不与条件装配混用——各 bean 由本类 {@code @Bean}
 * 显式装配（T1 审计/T3 角色 Clients 与线程池/T4 OrchestrationService 逐步加入）。
 * 开关关闭时本配置不生效：{@link SddProperties} 不绑定、编排 Mapper 不扫描
 * （Mapper 接口不标 {@code @Mapper}，MyBatis 自动扫描不兜底）、
 * OrchestrationService/Clients 均不装配；ChatService 经
 * {@code ObjectProvider<OrchestrationService>} 拿到 null，对话路径与迭代4
 * 逐字节一致（AC-1/AC-2）。
 *
 * <p>MybatisPlusInterceptor 由 ToolRuntimeConfig 注册（工具开关开时）；编排仅 insert
 * 不依赖分页拦截器，工具开关关闭时插入不受影响（AC-4/AC-25）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.sdd", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SddProperties.class)
@MapperScan(basePackages = "com.dj.ai.agentchat.orchestration.audit.mapper")
public class SddRuntimeConfig {

    /**
     * 编排审计表懒建表器：持 DataSource，首次编排审计路径经 ScriptUtils 执行
     * classpath agent-orchestration-schema.sql（无启动 runner，AC-60.3）。
     */
    @Bean
    public OrchestrationSchemaInitializer orchestrationSchemaInitializer(DataSource dataSource) {
        return new OrchestrationSchemaInitializer(dataSource);
    }

    /**
     * 编排跑次审计：agent_orchestration_run best-effort 落库；
     * SecretRedactor 仅工具开关开时在场（缺席时长度截断兜底，AC-36）。
     */
    @Bean
    public OrchestrationAuditService orchestrationAuditService(OrchestrationRunMapper mapper,
                                                               OrchestrationSchemaInitializer schemaInitializer,
                                                               ObjectProvider<SecretRedactor> redactorProvider) {
        return new OrchestrationAuditService(mapper, schemaInitializer, redactorProvider.getIfAvailable());
    }

    /**
     * 编排状态机线程池（T3/T4）：daemon cached 池，Flux.create + subscribeOn 驱动
     * Planner/Executor 同步循环；不占用 Web 容器/Reactor 线程，空闲回收、随 JVM 退出。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService sddOrchestrator() {
        return Executors.newCachedThreadPool(daemonThreadFactory("sdd-orchestrator"));
    }

    /**
     * 模型调用线程池（T3）：Planner/Executor 同步 .call() 在此执行，编排线程以
     * Future.get(剩余预算/单任务超时) 强时限等待；超时 cancel(true) best-effort 中断。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService sddModelCallPool() {
        return Executors.newCachedThreadPool(daemonThreadFactory("sdd-model-call"));
    }

    /** 同步模型调用统一超时包装（持 sdd-model-call 池）。 */
    @Bean
    public ModelInvoker sddModelInvoker(ExecutorService sddModelCallPool) {
        return new ModelInvoker(sddModelCallPool);
    }

    /** Planner 角色 Client（路由/再规划/汇总）。 */
    @Bean
    public PlannerClient plannerClient(ChatClient chatClient,
                                       SddProperties properties,
                                       ModelInvoker sddModelInvoker,
                                       OrchestrationAuditService orchestrationAuditService) {
        return new PlannerClient(chatClient, properties, sddModelInvoker, orchestrationAuditService);
    }

    /** Executor 角色 Client；SecretRedactor 缺席（工具开关关闭）时仅长度截断兜底。 */
    @Bean
    public ExecutorClient executorClient(ChatClient chatClient,
                                         SddProperties properties,
                                         ModelInvoker sddModelInvoker,
                                         OrchestrationAuditService orchestrationAuditService,
                                         ObjectProvider<SecretRedactor> redactorProvider) {
        return new ExecutorClient(chatClient, properties, sddModelInvoker,
                orchestrationAuditService, redactorProvider.getIfAvailable());
    }

    /**
     * 编排状态机（T4）：持 PlannerClient/ExecutorClient/Audit 与 sdd-orchestrator 池；
     * ChatService 经 ObjectProvider 可选注入（开关关闭时缺席）。
     */
    @Bean
    public OrchestrationService orchestrationService(PlannerClient plannerClient,
                                                     ExecutorClient executorClient,
                                                     OrchestrationAuditService orchestrationAuditService,
                                                     SddProperties properties,
                                                     ExecutorService sddOrchestrator) {
        return new OrchestrationService(plannerClient, executorClient, orchestrationAuditService,
                properties, sddOrchestrator);
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        return r -> {
            Thread t = new Thread(r, namePrefix);
            t.setDaemon(true);
            return t;
        };
    }
}
