package com.dj.ai.agentchat.dim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 迁移：DimProjectSchemaInitializer 单测（范式复刻 ChatMemorySchemaInitializerTest
 * 精简版）——mock DataSource/Connection/Statement，ScriptUtils 真实解析 classpath
 * db/dim-schema.sql，验证 dim_project 单语句执行、成功后短路、runner best-effort。
 */
class DimProjectSchemaInitializerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private DimProjectSchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        initializer = new DimProjectSchemaInitializer(dataSource);
    }

    @Test
    void ensureSchema_executesExactlyOneStatement() throws Exception {
        initializer.ensureSchema();

        verify(statement, times(1)).execute(org.mockito.ArgumentMatchers.anyString());
        verify(statement).execute(contains("CREATE TABLE IF NOT EXISTS dim_project"));
        verify(statement).execute(contains("UNIQUE KEY uk_dim_project_name"));
        verify(statement).close();
    }

    @Test
    void ensureSchema_secondCall_shortCircuitsWithoutConnection() throws Exception {
        initializer.ensureSchema();
        initializer.ensureSchema();

        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void schemaResource_exists() {
        ClassPathResource resource = new ClassPathResource("db/dim-schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getFilename()).isEqualTo("dim-schema.sql");
    }

    @Test
    void startupRunner_schemaFailure_isSwallowed() {
        DimProjectSchemaInitializer failingInitializer = mock(DimProjectSchemaInitializer.class);
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("MySQL down"))
                .when(failingInitializer).ensureSchema();
        DimProjectSchemaStartupRunner runner = new DimProjectSchemaStartupRunner(failingInitializer);

        // best-effort：任何异常仅 warn，绝不阻断启动
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(failingInitializer).ensureSchema();
    }
}
