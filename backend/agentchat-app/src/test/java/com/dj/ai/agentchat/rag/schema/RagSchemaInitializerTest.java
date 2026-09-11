package com.dj.ai.agentchat.rag.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2：RAG 建表器与启动 runner（范式复刻 ToolSchemaInitializerTest）。
 * mock DataSource/Connection/Statement：ScriptUtils 真实解析 classpath
 * db/rag-schema.sql，验证扩展/两表/HNSW 五条语句执行、成功后短路、
 * 失败传播且不置位、runner best-effort。
 */
class RagSchemaInitializerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private RagSchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        initializer = new RagSchemaInitializer(dataSource);
    }

    @Test
    void ensureSchema_firstCall_executesExtensionTablesAndHnsw() throws Exception {
        initializer.ensureSchema();

        verify(dataSource).getConnection();
        // CREATE EXTENSION vector
        verify(statement).execute(contains("CREATE EXTENSION IF NOT EXISTS vector"));
        // 两张表
        verify(statement).execute(contains("CREATE TABLE IF NOT EXISTS rag_document"));
        verify(statement).execute(contains("CREATE TABLE IF NOT EXISTS rag_chunk"));
        // 向量列 1024 维 + HNSW cosine 索引
        verify(statement).execute(contains("vector(1024)"));
        verify(statement).execute(contains("hnsw (embedding vector_cosine_ops)"));
        verify(statement).close();
    }

    @Test
    void ensureSchema_executesExactlyFiveStatements() throws Exception {
        initializer.ensureSchema();

        // 扩展 1 + 两表 2 + 两索引 2 = 5
        verify(statement, times(5)).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ensureSchema_secondCall_shortCircuitsWithoutConnection() throws Exception {
        initializer.ensureSchema();
        initializer.ensureSchema();

        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void ensureSchema_connectionFailure_propagates_andRetriesNextTime() throws Exception {
        CannotGetJdbcConnectionException failure =
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
        when(dataSource.getConnection()).thenThrow(failure);

        assertThatThrownBy(initializer::ensureSchema)
                .isInstanceOf(CannotGetJdbcConnectionException.class);
        assertThatThrownBy(initializer::ensureSchema)
                .isInstanceOf(CannotGetJdbcConnectionException.class);

        // 失败不置位：PG 恢复后下次调用仍会重试
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void schemaResource_exists() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/rag-schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getFilename()).isEqualTo("rag-schema.sql");
    }

    @Test
    void startupRunner_happyPath_ensuresSchema() throws Exception {
        RagSchemaStartupRunner runner = new RagSchemaStartupRunner(initializer);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(dataSource).getConnection();
    }

    @Test
    void startupRunner_schemaFailure_isSwallowed() throws Exception {
        RagSchemaInitializer failingInitializer = mock(RagSchemaInitializer.class);
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("PG down"))
                .when(failingInitializer).ensureSchema();
        RagSchemaStartupRunner runner = new RagSchemaStartupRunner(failingInitializer);

        // best-effort：任何异常仅 warn，绝不阻断启动
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(failingInitializer).ensureSchema();
    }
}
