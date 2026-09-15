package com.dj.ai.agentchat.rag.dim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 追加：DimRepository SQL 单测（mock JdbcTemplate 钉 SQL 文本与参数序）——
 * 标签改名/删除联动的 JSONB 操作符（pgjdbc `??` 转义 + `?::text` cast）、
 * 项目改名联动、引用计数、标签列表 jsonb 展开。真实 PG 语义由冒烟覆盖。
 */
class DimRepositoryTest {

    private JdbcTemplate jdbc;
    private DimRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new DimRepository(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProjects_sqlCarriesDocCountSubquery() {
        when(jdbc.query(contains("FROM dim_project"), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.listProjects();

        verify(jdbc).query(contains("(SELECT count(*) FROM rag_document d WHERE d.project = p.name)"),
                any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listTags_sqlExpandsJsonbArray() {
        when(jdbc.query(contains("jsonb_array_elements_text"), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.listTags();

        verify(jdbc).query(contains("GROUP BY t.name"), any(RowMapper.class));
    }

    @Test
    void renameTag_sqlEscapesKeyExistsOperatorAndCastsText() {
        repository.renameTag("退货", "换货");

        // pgjdbc：JSONB 键存在 ? 运算符双写 ?? 转义；?::text cast 防 null OID 推断失败
        verify(jdbc).update(contains("tags ?? ?::text"), eq("退货"), eq("换货"), eq("退货"));
    }

    @Test
    void renameTag_sqlDeduplicatesWhenTargetAlreadyPresent() {
        repository.renameTag("退货", "售后");

        // 目标标签已存在于同一数组时 jsonb_agg(DISTINCT ...) 去重（集合语义）
        verify(jdbc).update(contains("jsonb_agg(DISTINCT new_v)"),
                eq("退货"), eq("售后"), eq("退货"));
    }

    @Test
    void deleteTag_sqlUsesJsonbMinusOperatorWithEscapedExists() {
        repository.deleteTag("退货");

        verify(jdbc).update(contains("tags = tags - ?::text"), eq("退货"), eq("退货"));
    }

    @Test
    void renameProjectDocs_updatesRowsByName() {
        repository.renameProjectDocs("订单域", "交易域");

        verify(jdbc).update(contains("UPDATE rag_document SET project = ?"),
                eq("交易域"), eq("订单域"));
    }

    @Test
    void countDocsByTag_sqlEscapesKeyExistsOperator() {
        when(jdbc.queryForObject(contains("tags ?? ?::text"), eq(Long.class), eq("售后")))
                .thenReturn(3L);

        long n = repository.countDocsByTag("售后");

        assertThat(n).isEqualTo(3L);
    }

    @Test
    void projectExists_queriesByName() {
        when(jdbc.queryForObject(contains("FROM dim_project WHERE name = ?"),
                eq(Long.class), eq("订单域"))).thenReturn(1L);

        assertThat(repository.projectExists("订单域")).isTrue();
    }

    @Test
    void insertProject_returnsGeneratedId() {
        when(jdbc.queryForObject(contains("INSERT INTO dim_project"), eq(Long.class),
                eq("订单域"), eq("订单相关制度"))).thenReturn(11L);

        long id = repository.insertProject("订单域", "订单相关制度");

        assertThat(id).isEqualTo(11L);
    }

    @Test
    void deleteProject_deletesById() {
        when(jdbc.update(contains("DELETE FROM dim_project WHERE id = ?"), eq(5L)))
                .thenReturn(1);

        assertThat(repository.deleteProject(5L)).isEqualTo(1);
    }
}
