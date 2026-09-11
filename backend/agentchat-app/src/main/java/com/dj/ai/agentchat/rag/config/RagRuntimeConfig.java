package com.dj.ai.agentchat.rag.config;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import com.dj.ai.agentchat.rag.admin.service.KbDocumentService;
import com.dj.ai.agentchat.rag.admin.service.KbHealthService;
import com.dj.ai.agentchat.rag.chunk.TextChunker;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.schema.RagSchemaStartupRunner;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * RAG 知识库运行时装配（迭代6）：整体受 {@code app.rag.enabled}（默认 false）开关收口。
 *
 * <p>范式复刻 {@code SddRuntimeConfig}/{@code ToolRuntimeConfig}：条件装配 + 构造型注解
 * 不混用——各 bean 由本类 {@code @Bean} 显式装配，消费侧（ChatService/ExecutorClient/
 * AdminAuthInterceptor）经 {@code ObjectProvider} 可选注入。开关关闭时本配置不生效：
 * {@link RagProperties} 不绑定、PG 连接池不初始化、Ollama 客户端不创建、Advisor 不挂载，
 * 对话链路与迭代5 逐字节一致。
 *
 * <p><b>双数据源</b>：本类一旦生效即声明 RAG 自己的 PG {@link DataSource}，Boot 的
 * {@code DataSourceConfiguration.Hikari} 会因 {@code @ConditionalOnMissingBean(DataSource)}
 * 回退，故这里同时手工重建 MySQL 主数据源并标 {@link Primary @Primary}（参数仍来自
 * {@code spring.datasource.*} 绑定的 {@link DataSourceProperties}，连接行为与自动配置期
 * 一致：Hikari 懒连接、initializationFailTimeout=-1）。MyBatis-Plus/记忆/工具/编排 Mapper
 * 全部继续走 @Primary 的 MySQL；RAG 侧仅经 {@link #ragJdbcTemplate} 限定注入访问 PG。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagRuntimeConfig {

    /**
     * MySQL 主数据源手工重建：RAG 生效后 Boot Hikari 自动配置回退，由本 bean 承接
     * {@code spring.datasource.*}（URL/账号/驱动 + 既有 pool-name/懒连接语义），
     * 保持 MyBatis-Plus 全家桶主数据源不变。
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("DjAgentHikariPool");
        // 与 spring.datasource.hikari.initialization-fail-timeout=-1 等价：MySQL 不可达也能启动
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    /**
     * RAG 专用 PG/pgvector 数据源：绑定 {@code app.rag.datasource.*}，独立 Hikari 池，
     * 不参与 {@code spring.datasource} 自动配置；懒连接（PG 不可达不阻断启动）。
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource ragDataSource(RagProperties properties) {
        RagProperties.Datasource cfg = properties.getDatasource();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("RagPgHikariPool");
        dataSource.setJdbcUrl(cfg.getJdbcUrl());
        dataSource.setUsername(cfg.getUsername());
        dataSource.setPassword(cfg.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(4);
        // 与主数据源一致：启动不探测，首借连接失败由上层懒建表/降级路径处理
        dataSource.setInitializationFailTimeout(-1);
        // 本机/局域网 PG：5s 借不到连接即失败（默认 30s 会拖垮启动期 best-effort 与 Advisor 降级时延）
        dataSource.setConnectionTimeout(5000);
        return dataSource;
    }

    /** RAG 侧唯一 PG 访问入口（限定注入，绝不误连 MySQL）。 */
    @Bean
    public JdbcTemplate ragJdbcTemplate(@Qualifier("ragDataSource") DataSource ragDataSource) {
        return new JdbcTemplate(ragDataSource);
    }

    /**
     * PG 专属事务管理器：仓储同名覆盖/重建的 {@code @Transactional("ragTransactionManager")}
     * 走它，不与 MySQL 主事务管理器串库。
     */
    @Bean
    public TransactionManager ragTransactionManager(
            @Qualifier("ragDataSource") DataSource ragDataSource) {
        return new DataSourceTransactionManager(ragDataSource);
    }

    /**
     * 指向本机 Ollama 的 OpenAI 兼容客户端（M7 {@link OpenAiApi} 手工装配，避开自动配置，
     * 不与方舟 chat 的 OpenAiApi 冲突）；连接与读超时取 app.rag.ollama.timeout-ms。
     */
    @Bean
    public OpenAiApi ragOllamaApi(RagProperties properties) {
        RagProperties.Ollama cfg = properties.getOllama();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        return OpenAiApi.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey("ollama") // Ollama 不校验 key，占位非空即可（M7 断言非空白）
                .embeddingsPath("/v1/embeddings")
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * bge-m3（1024 维）embedding 模型；只构造不探测，首次调用才连 Ollama。
     * 返回具体类型 {@link OpenAiEmbeddingModel}（非接口 EmbeddingModel）：M7
     * OpenAiEmbeddingAutoConfiguration 的兜底条件是 @ConditionalOnMissingBean
     * (OpenAiEmbeddingModel.class)，按 @Bean 工厂方法声明类型判定，只有具体类型
     * 才能让自动配置正确回退、避免装出第二个指向方舟的 embedding 模型。
     */
    @Bean
    public OpenAiEmbeddingModel ragEmbeddingModel(@Qualifier("ragOllamaApi") OpenAiApi ragOllamaApi,
                                                  RagProperties properties) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getOllama().getModel())
                .build();
        return new OpenAiEmbeddingModel(ragOllamaApi, MetadataMode.EMBED, options);
    }

    /** Embedding 服务：分批单请求、顺序对齐、异常分类。 */
    @Bean
    public RagEmbeddingService ragEmbeddingService(
            @Qualifier("ragEmbeddingModel") EmbeddingModel ragEmbeddingModel,
            RagProperties properties) {
        return new RagEmbeddingService(ragEmbeddingModel, properties);
    }

    /** pgvector 扩展 + RAG 两表懒建表器（注入 PG 限定数据源）。 */
    @Bean
    public RagSchemaInitializer ragSchemaInitializer(
            @Qualifier("ragDataSource") DataSource ragDataSource) {
        return new RagSchemaInitializer(ragDataSource);
    }

    /** 启动期 best-effort 建表：PG 可达则提前就绪，不可达仅 warn 不阻断启动。 */
    @Bean
    public RagSchemaStartupRunner ragSchemaStartupRunner(RagSchemaInitializer ragSchemaInitializer) {
        return new RagSchemaStartupRunner(ragSchemaInitializer);
    }

    /** 文本切片器：标题感知 + 贪心打包/滑窗，参数取 app.rag.chunk.*。 */
    @Bean
    public TextChunker ragTextChunker(RagProperties properties) {
        return new TextChunker(properties.getChunk());
    }

    /** pgvector 仓储：只认 ragJdbcTemplate（PG），写事务走 ragTransactionManager。 */
    @Bean
    public KbRepository kbRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate) {
        return new KbRepository(ragJdbcTemplate);
    }

    /** 管理端文档编排：校验/解码/切片/embedding/落库，FAILED 行可重试。 */
    @Bean
    public KbDocumentService kbDocumentService(KbRepository kbRepository,
                                               RagEmbeddingService ragEmbeddingService,
                                               TextChunker ragTextChunker,
                                               RagProperties properties,
                                               RagSchemaInitializer ragSchemaInitializer) {
        return new KbDocumentService(kbRepository, ragEmbeddingService, ragTextChunker,
                properties, ragSchemaInitializer);
    }

    /**
     * 健康检查服务：Ollama 轻探活（一次最短 embedding）+ PG 计数探测，分项独立降级。
     */
    @Bean
    public KbHealthService kbHealthService(RagEmbeddingService ragEmbeddingService,
                                           KbRepository kbRepository) {
        return new KbHealthService(ragEmbeddingService, kbRepository);
    }

    /**
     * RAG 常驻 Advisor（同步+流式双接口）：消费侧以 ObjectProvider 可选注入，
     * 开关关闭时本 bean 不存在，对话链路与迭代5 完全一致。
     */
    @Bean
    public RagAdvisor ragAdvisor(RagEmbeddingService ragEmbeddingService,
                                 KbRepository kbRepository,
                                 RagProperties properties) {
        return new RagAdvisor(ragEmbeddingService, kbRepository, properties);
    }
}
