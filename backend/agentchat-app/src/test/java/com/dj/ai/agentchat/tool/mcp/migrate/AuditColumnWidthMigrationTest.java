package com.dj.ai.agentchat.tool.mcp.migrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1：{@link AuditColumnWidthMigration} 四路径（AC-19 迁移幂等）：
 * 列宽 64 → 执行 ALTER；列宽已 ≥128 → 跳过；表/列不存在（空结果）→ 跳过；
 * 查询/ALTER 异常 → WARN 不抛出不阻断。
 */
class AuditColumnWidthMigrationTest {

    private JdbcTemplate jdbc;
    private AuditColumnWidthMigration migration;

    @BeforeEach
    void setUp() {
        jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        migration = new AuditColumnWidthMigration(jdbc);
    }

    @Test
    void narrowColumn_width64_executesAlterTo128() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(64));

        assertThat(migration.migrate()).isTrue();

        verify(jdbc).execute(contains("ALTER TABLE agent_tool_call_log MODIFY COLUMN tool_name VARCHAR(128)"));
    }

    @Test
    void alreadyWide_width128_skipsAlter_idempotent() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(128));

        assertThat(migration.migrate()).isFalse();

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void tableMissing_emptyResult_skipsSilently() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(migration.migrate()).isFalse();

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void queryThrows_warnsAndDoesNotPropagate() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenThrow(new CannotGetJdbcConnectionException("连接拒绝"));

        assertThatCode(() -> migration.migrate()).doesNotThrowAnyException();
        assertThat(migration.migrate()).isFalse();
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void alterThrows_warnsAndDoesNotPropagate() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(64));
        org.mockito.Mockito.doThrow(new RuntimeException("ALTER command denied"))
                .when(jdbc).execute(anyString());

        assertThatCode(() -> migration.migrate()).doesNotThrowAnyException();
        assertThat(migration.migrate()).isFalse();
    }
}
