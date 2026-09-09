package com.dj.ai.agentchat.memory.mybatis;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3：懒建表器与启动 runner（实证 5/8）。
 *
 * <p>mock DataSource/Connection/Statement：ScriptUtils 真实执行 classpath schema.sql
 * （经 mock Statement 捕获执行的 SQL），验证首次执行、成功后短路、失败传播且不置位、
 * 启动 runner best-effort 吞异常。
 */
class ChatMemorySchemaInitializerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ChatMemorySchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        initializer = new ChatMemorySchemaInitializer(dataSource);
    }

    @Test
    void ensureSchema_firstCall_executesSchemaScriptFromClasspath() throws Exception {
        initializer.ensureSchema();

        verify(dataSource).getConnection();
        verify(connection).createStatement();
        // 两条 CREATE TABLE 经 mock Statement 执行（真实解析 classpath db/chat-memory-schema.sql）
        verify(statement, times(2)).execute(org.mockito.ArgumentMatchers.contains("CREATE TABLE"));
        verify(statement).close();
    }

    @Test
    void ensureSchema_secondCall_shortCircuitsWithoutConnection() throws Exception {
        initializer.ensureSchema();
        initializer.ensureSchema();

        // 成功后 AtomicBoolean 短路：只取一次连接
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void ensureSchema_connectionFailure_propagatesDataAccessException_andRetriesNextTime() throws Exception {
        CannotGetJdbcConnectionException failure =
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
        when(dataSource.getConnection()).thenThrow(failure);

        assertThatThrownBy(initializer::ensureSchema)
                .isInstanceOf(CannotGetJdbcConnectionException.class);
        assertThatThrownBy(initializer::ensureSchema)
                .isInstanceOf(CannotGetJdbcConnectionException.class);

        // 失败不置位：DB 恢复后下次调用仍会重试（每次都尝试取连接）
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void schemaResource_existsAndContainsBothTables() {
        ClassPathResource resource = new ClassPathResource("db/chat-memory-schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getFilename()).isEqualTo("chat-memory-schema.sql");
    }

    @Test
    void startupRunner_happyPath_invokesEnsureSchema() throws Exception {
        ChatMemorySchemaStartupRunner runner = new ChatMemorySchemaStartupRunner(initializer);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(dataSource).getConnection();
    }

    @Test
    void startupRunner_failure_isSwallowedAndDoesNotPropagate() throws Exception {
        ChatMemorySchemaInitializer failingInitializer = mock(ChatMemorySchemaInitializer.class);
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("DB down"))
                .when(failingInitializer).ensureSchema();
        ChatMemorySchemaStartupRunner runner = new ChatMemorySchemaStartupRunner(failingInitializer);

        // best-effort：任何异常仅 warn，绝不阻断启动（AC-16/AC-19）
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(failingInitializer).ensureSchema();
        verify(dataSource, never()).getConnection();
    }
}
