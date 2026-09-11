package com.dj.ai.agentchat.rag.config;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.advisor.RagAdvisor;
import com.dj.ai.agentchat.rag.admin.service.KbDocumentService;
import com.dj.ai.agentchat.rag.admin.service.KbHealthService;
import com.dj.ai.agentchat.rag.chunk.TextChunker;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.schema.RagSchemaStartupRunner;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1：RAG 开关条件装配。
 * 默认（app.rag.enabled 缺省=false）：RagProperties/RagRuntimeConfig 均不装配，
 * 应用上下文照常刷新（RAG bean 缺席 = 迭代5 路径零触达）；
 * enabled=true：配置与属性 bean 装配，缺省值正确；
 * 全键可经配置覆盖绑定（APP_RAG_* env 同构）。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class RagRuntimeConfigDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void ragBeans_areAbsent_whenSwitchMissingOrFalse() {
        assertThat(context.getBeansOfType(RagProperties.class)).isEmpty();
        assertThat(context.getBeansOfType(RagRuntimeConfig.class)).isEmpty();
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.rag.enabled=true"
})
class RagRuntimeConfigEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("dataSource")
    private DataSource mysqlDataSource;

    @Autowired
    @Qualifier("ragDataSource")
    private DataSource ragDataSource;

    @Autowired
    @Qualifier("ragJdbcTemplate")
    private JdbcTemplate ragJdbcTemplate;

    @Test
    void dualDataSources_assembled_mysqlStaysPrimary_pgIsRagScoped() {
        Map<String, DataSource> beans = context.getBeansOfType(DataSource.class);
        assertThat(beans).containsKeys("dataSource", "ragDataSource");
        // MySQL 仍是 @Primary（MyBatis-Plus/记忆/工具/编排不受影响）
        assertThat(context.getBean(DataSource.class)).isSameAs(mysqlDataSource);
        assertThat(((HikariDataSource) mysqlDataSource).getJdbcUrl()).contains("13306/dj_agent");
        assertThat(((HikariDataSource) mysqlDataSource).getPoolName()).isEqualTo("DjAgentHikariPool");
        assertThat(((HikariDataSource) mysqlDataSource).getInitializationFailTimeout()).isEqualTo(-1);
        // PG 第二数据源独立绑定 app.rag.datasource.*
        assertThat(((HikariDataSource) ragDataSource).getJdbcUrl())
                .isEqualTo("jdbc:postgresql://127.0.0.1:15432/ai_vector");
        assertThat(((HikariDataSource) ragDataSource).getPoolName()).isEqualTo("RagPgHikariPool");
        assertThat(((HikariDataSource) ragDataSource).getConnectionTimeout()).isEqualTo(5000);
        assertThat(ragJdbcTemplate.getDataSource()).isSameAs(ragDataSource);
    }

    @Test
    void schemaTrio_areAssembled() {
        assertThat(context.getBeansOfType(RagSchemaInitializer.class)).isNotEmpty();
        assertThat(context.getBeansOfType(RagSchemaStartupRunner.class)).isNotEmpty();
    }

    @Test
    void ollamaEmbeddingBeans_areAssembled_withoutAutoConfigClash() {
        // 手工装配的 Ollama 三件套在场
        assertThat(context.getBeansOfType(OpenAiApi.class)).containsKey("ragOllamaApi");
        assertThat(context.getBeansOfType(RagEmbeddingService.class)).containsKey("ragEmbeddingService");
        // 仅一个 EmbeddingModel（Ark 自动配置不得因 base-url 同前缀再装一个 embedding 模型）
        assertThat(context.getBeansOfType(EmbeddingModel.class))
                .hasSize(1)
                .containsKey("ragEmbeddingModel");
    }

    @Test
    void knowledgeChainBeans_areAssembled_textChunkerRepositoryServiceAndAdvisor() {
        assertThat(context.getBeansOfType(TextChunker.class)).containsKey("ragTextChunker");
        assertThat(context.getBeansOfType(KbRepository.class)).containsKey("kbRepository");
        assertThat(context.getBeansOfType(KbDocumentService.class)).containsKey("kbDocumentService");
        assertThat(context.getBeansOfType(KbHealthService.class)).containsKey("kbHealthService");
        assertThat(context.getBeansOfType(RagAdvisor.class)).containsKey("ragAdvisor");
    }

    @Test
    void ragBeans_arePresent_whenEnabled_withDefaults() {
        RagProperties props = context.getBean(RagProperties.class);
        assertThat(props).isNotNull();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getDatasource().getJdbcUrl())
                .isEqualTo("jdbc:postgresql://127.0.0.1:15432/ai_vector");
        assertThat(props.getDatasource().getUsername()).isEqualTo("postgres");
        assertThat(props.getOllama().getBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(props.getOllama().getModel()).isEqualTo("bge-m3");
        assertThat(props.getOllama().getTimeoutMs()).isEqualTo(10000);
        assertThat(props.getChunk().getMaxChars()).isEqualTo(500);
        assertThat(props.getChunk().getOverlap()).isEqualTo(80);
        assertThat(props.getChunk().isHeadingAware()).isTrue();
        assertThat(props.getRetrieve().getTopK()).isEqualTo(4);
        assertThat(props.getRetrieve().getMinScore()).isEqualTo(0.45d);
        assertThat(props.getUpload().getMaxFileBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(props.getUpload().getAllowedExt()).containsExactly("md", "markdown", "txt");
        assertThat(context.getBeansOfType(RagRuntimeConfig.class)).isNotEmpty();
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.rag.enabled=true",
        "app.rag.datasource.jdbc-url=jdbc:postgresql://pg.example:5432/kb",
        "app.rag.datasource.username=kbuser",
        "app.rag.datasource.password=kbpass",
        "app.rag.ollama.base-url=http://ollama.example:11434",
        "app.rag.ollama.model=bge-large-zh",
        "app.rag.ollama.timeout-ms=3000",
        "app.rag.chunk.max-chars=800",
        "app.rag.chunk.overlap=100",
        "app.rag.chunk.heading-aware=false",
        "app.rag.retrieve.top-k=6",
        "app.rag.retrieve.min-score=0.6",
        "app.rag.upload.max-file-bytes=2048",
        "app.rag.upload.allowed-ext=md,txt"
})
class RagPropertiesBindingTest {

    @Autowired
    private RagProperties props;

    @Test
    void allKeys_bindFromConfig() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getDatasource().getJdbcUrl())
                .isEqualTo("jdbc:postgresql://pg.example:5432/kb");
        assertThat(props.getDatasource().getUsername()).isEqualTo("kbuser");
        assertThat(props.getDatasource().getPassword()).isEqualTo("kbpass");
        assertThat(props.getOllama().getBaseUrl()).isEqualTo("http://ollama.example:11434");
        assertThat(props.getOllama().getModel()).isEqualTo("bge-large-zh");
        assertThat(props.getOllama().getTimeoutMs()).isEqualTo(3000);
        assertThat(props.getChunk().getMaxChars()).isEqualTo(800);
        assertThat(props.getChunk().getOverlap()).isEqualTo(100);
        assertThat(props.getChunk().isHeadingAware()).isFalse();
        assertThat(props.getRetrieve().getTopK()).isEqualTo(6);
        assertThat(props.getRetrieve().getMinScore()).isEqualTo(0.6d);
        assertThat(props.getUpload().getMaxFileBytes()).isEqualTo(2048L);
        assertThat(props.getUpload().getAllowedExt()).isEqualTo(List.of("md", "txt"));
    }
}
