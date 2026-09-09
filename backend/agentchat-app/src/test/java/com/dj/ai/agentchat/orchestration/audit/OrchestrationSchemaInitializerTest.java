package com.dj.ai.agentchat.orchestration.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1：编排审计表懒建表器（AC-60.3，范式复刻 ToolSchemaInitializer）——
 * 首次 record 路径触发 classpath agent-orchestration-schema.sql（CREATE TABLE IF NOT EXISTS，
 * 幂等、无外键）；成功后 AtomicBoolean 短路；连接失败原样传播且不置位（DB 恢复后重试）。
 */
class OrchestrationSchemaInitializerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private OrchestrationSchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        initializer = new OrchestrationSchemaInitializer(dataSource);
    }

    @Test
    void ensureSchema_firstCall_executesSchemaScriptFromClasspath() throws Exception {
        initializer.ensureSchema();

        verify(dataSource).getConnection();
        verify(connection).createStatement();
        // 单条 CREATE TABLE agent_orchestration_run
        verify(statement, times(1)).execute(contains("CREATE TABLE"));
        verify(statement).close();
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

        // 失败不置位：DB 恢复后下次调用仍会重试
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void schemaResource_exists() {
        ClassPathResource resource = new ClassPathResource("db/agent-orchestration-schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getFilename()).isEqualTo("agent-orchestration-schema.sql");
    }
}
