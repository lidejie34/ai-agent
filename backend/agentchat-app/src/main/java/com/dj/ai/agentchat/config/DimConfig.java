package com.dj.ai.agentchat.config;

import com.dj.ai.agentchat.dim.DimProjectRepository;
import com.dj.ai.agentchat.dim.DimProjectSchemaInitializer;
import com.dj.ai.agentchat.dim.DimProjectSchemaStartupRunner;
import com.dj.ai.agentchat.dim.DimProjectService;
import com.dj.ai.agentchat.rag.dim.DimRagRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 维度（受管项目）装配（迭代10 迁移）：dim_project 在 MySQL 主库，<b>无条件装配</b>——
 * 不随 app.rag.enabled 开关（RAG 关闭也能维护项目，供后续工具调度复用）；
 * 也不随 app.tools.enabled（维度是独立共享实体）。
 *
 * <p>DataSource 注入主库（MySQL 为 @Primary，rag PG 数据源有 Qualifier 隔离）；
 * JdbcTemplate 不复用 Boot 自动配置（RAG 开启时与 ragJdbcTemplate 并存有歧义风险，
 * 冒烟实证曾误注 PG 侧）——显式声明 dimJdbcTemplate 专属 bean。
 * 与 rag 侧文档的联动经 ObjectProvider 懒取，RAG 关闭时自动降级（计数 0、联动跳过）。
 */
@Configuration
public class DimConfig {

    /** 维度专属 JdbcTemplate：显式绑 @Primary 主库（MySQL），与 ragJdbcTemplate（PG）隔离。 */
    @Bean
    public JdbcTemplate dimJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DimProjectSchemaInitializer dimProjectSchemaInitializer(DataSource dataSource) {
        return new DimProjectSchemaInitializer(dataSource);
    }

    @Bean
    public DimProjectSchemaStartupRunner dimProjectSchemaStartupRunner(
            DimProjectSchemaInitializer dimProjectSchemaInitializer) {
        return new DimProjectSchemaStartupRunner(dimProjectSchemaInitializer);
    }

    @Bean
    public DimProjectRepository dimProjectRepository(
            @Qualifier("dimJdbcTemplate") JdbcTemplate dimJdbcTemplate) {
        return new DimProjectRepository(dimJdbcTemplate);
    }

    @Bean
    public DimProjectService dimProjectService(
            DimProjectRepository dimProjectRepository,
            DimProjectSchemaInitializer dimProjectSchemaInitializer,
            ObjectProvider<DimRagRepository> dimRagRepositoryProvider) {
        return new DimProjectService(dimProjectRepository, dimProjectSchemaInitializer,
                dimRagRepositoryProvider);
    }
}
