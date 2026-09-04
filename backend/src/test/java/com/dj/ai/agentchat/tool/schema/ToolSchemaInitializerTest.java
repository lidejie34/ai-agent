package com.dj.ai.agentchat.tool.schema;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1：工具表懒建表器与启动 runner（范式复刻 ChatMemorySchemaInitializerTest）。
 * mock DataSource/Connection/Statement：ScriptUtils 真实执行 classpath
 * agent-tool-schema.sql，验证首次执行、成功后短路、失败传播且不置位、runner best-effort。
 */
class ToolSchemaInitializerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ToolSchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        initializer = new ToolSchemaInitializer(dataSource);
    }

    @Test
    void ensureSchema_firstCall_executesSchemaScriptFromClasspath() throws Exception {
        initializer.ensureSchema();

        verify(dataSource).getConnection();
        verify(connection).createStatement();
        // 两条 CREATE TABLE 经 mock Statement 执行（真实解析 classpath db/agent-tool-schema.sql）
        verify(statement, times(2)).execute(contains("CREATE TABLE"));
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
    void schemaResource_existsAndContainsBothTables() {
        ClassPathResource resource = new ClassPathResource("db/agent-tool-schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getFilename()).isEqualTo("agent-tool-schema.sql");
    }

    @Test
    void startupRunner_happyPath_ensuresSchemaAndSeeds() throws Exception {
        ToolSeeder seeder = mock(ToolSeeder.class);
        ToolSchemaStartupRunner runner = new ToolSchemaStartupRunner(initializer, seeder);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(dataSource).getConnection();
        verify(seeder).seedIfAbsent();
    }

    @Test
    void startupRunner_schemaFailure_isSwallowed_andSeederNotInvoked() throws Exception {
        ToolSchemaInitializer failingInitializer = mock(ToolSchemaInitializer.class);
        ToolSeeder seeder = mock(ToolSeeder.class);
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("DB down"))
                .when(failingInitializer).ensureSchema();
        ToolSchemaStartupRunner runner = new ToolSchemaStartupRunner(failingInitializer, seeder);

        // best-effort：任何异常仅 warn，绝不阻断启动（AC-5）
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(failingInitializer).ensureSchema();
        verify(seeder, never()).seedIfAbsent();
    }

    @Test
    void startupRunner_seederFailure_isSwallowed() throws Exception {
        ToolSchemaInitializer okInitializer = mock(ToolSchemaInitializer.class);
        ToolSeeder failingSeeder = mock(ToolSeeder.class);
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("DB down"))
                .when(failingSeeder).seedIfAbsent();
        ToolSchemaStartupRunner runner = new ToolSchemaStartupRunner(okInitializer, failingSeeder);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(okInitializer).ensureSchema();
        verify(failingSeeder).seedIfAbsent();
    }
}
