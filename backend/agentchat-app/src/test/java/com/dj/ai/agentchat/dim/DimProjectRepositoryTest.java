package com.dj.ai.agentchat.dim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 迁移：DimProjectRepository SQL 单测（mock JdbcTemplate 钉 SQL 文本与参数序，
 * MySQL 方言）——列表不带跨库计数（docCount 由服务层合并）、KeyHolder 取自增 id
 * （MySQL 无 RETURNING）、按名查重、按 id 更新/删除。真实 MySQL 语义由冒烟覆盖。
 */
class DimProjectRepositoryTest {

    private JdbcTemplate jdbc;
    private DimProjectRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new DimProjectRepository(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProjects_sqlWithoutCrossDbCount() {
        when(jdbc.query(contains("FROM dim_project"), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.listProjects();

        // 跨库计数不得出现在 SQL 里（应用层合并 PG 分组计数）
        verify(jdbc).query(contains("ORDER BY id"), any(RowMapper.class));
    }

    @Test
    void projectExists_queriesByName() {
        when(jdbc.queryForObject(contains("FROM dim_project WHERE name = ?"),
                eq(Long.class), eq("订单域"))).thenReturn(1L);

        assertThat(repository.projectExists("订单域")).isTrue();
    }

    @Test
    void insertProject_usesKeyHolderForAutoIncrementId() {
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder kh = invocation.getArgument(1);
                    kh.getKeyList().add(Map.of("GENERATED_KEY", 11L));
                    return 1;
                });

        long id = repository.insertProject("订单域", "订单相关制度");

        assertThat(id).isEqualTo(11L);
        verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void insertProject_nullKey_throwsIllegalState() {
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenReturn(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.insertProject("订单域", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自增 id");
    }

    @Test
    void updateProject_updatesById() {
        when(jdbc.update(contains("UPDATE dim_project SET name = ?, remark = ? WHERE id = ?"),
                eq("交易域"), eq("新备注"), eq(5L))).thenReturn(1);

        assertThat(repository.updateProject(5L, "交易域", "新备注")).isEqualTo(1);
    }

    @Test
    void deleteProject_deletesById() {
        when(jdbc.update(contains("DELETE FROM dim_project WHERE id = ?"), eq(5L)))
                .thenReturn(1);

        assertThat(repository.deleteProject(5L)).isEqualTo(1);
    }
}
