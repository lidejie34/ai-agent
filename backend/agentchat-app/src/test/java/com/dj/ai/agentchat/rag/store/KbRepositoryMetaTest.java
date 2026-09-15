package com.dj.ai.agentchat.rag.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10：KbRepository 维度元数据 SQL 单测（mock JdbcTemplate 钉 SQL 文本与参数序）——
 * INSERT 带 project/tags（?::jsonb）、过滤检索 doc 维 prefilter（?| string_to_array）、
 * 过滤列表、updateMeta、tagsLiteral/parseTags 纯函数、旧签名委托（null/"[]"）。
 * 真实 PG JSONB 语义由冒烟覆盖。
 */
class KbRepositoryMetaTest {

    private JdbcTemplate jdbc;
    private KbRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new KbRepository(jdbc);
    }

    private static List<float[]> vectors(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new float[]{0.1f * i, -0.2f})
                .toList();
    }

    @Test
    void saveReady_withMeta_insertArgsCarryProjectAndTagsJsonb() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);

        repository.saveReady("售后.md", 100, "原文", "h", List.of("片1"), vectors(1),
                "订单域", List.of("售后", "退货"));

        ArgumentCaptor<Object[]> docArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("?::jsonb"), eq(Long.class), docArgs.capture());
        Object[] args = docArgs.getValue();
        assertThat(args).containsExactly("售后.md", 100, "原文", "h", 1,
                RagDocument.STATUS_READY, null, "订单域", "[\"售后\",\"退货\"]");
    }

    @Test
    void saveReady_legacySignature_delegatesNullProjectAndEmptyTags() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(9L);

        repository.saveReady("a.md", 1, "c", "h", List.of("x"), vectors(1));

        ArgumentCaptor<Object[]> docArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("INSERT INTO rag_document"), eq(Long.class),
                docArgs.capture());
        Object[] args = docArgs.getValue();
        assertThat(args[7]).isNull();
        assertThat(args[8]).isEqualTo("[]");
    }

    @Test
    void saveFailed_withMeta_carriesMetaIntoFailedRow() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);

        repository.saveFailed("坏.md", 10, "原文", "h", "超时", "物流域", List.of("承运"));

        ArgumentCaptor<Object[]> docArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("INSERT INTO rag_document"), eq(Long.class),
                docArgs.capture());
        Object[] args = docArgs.getValue();
        assertThat(args[5]).isEqualTo(RagDocument.STATUS_FAILED);
        assertThat(args[7]).isEqualTo("物流域");
        assertThat(args[8]).isEqualTo("[\"承运\"]");
    }

    @Test
    void search_unfiltered_fourArgDelegatesToLegacySqlText() {
        repository.search(new float[]{0.1f}, 4, null, List.of());

        // 无维度 → 迭代6 原始 SQL：无 WHERE、无 rag_document 子查询
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("ORDER BY embedding <=>"), any(org.springframework.jdbc.core.RowMapper.class),
                params.capture());
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(
                        sql -> !sql.contains("rag_document") && !sql.contains("WHERE")),
                any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        assertThat(params.getValue()).hasSize(3); // vector, vector, topK
    }

    @Test
    void search_filteredByProjectAndTags_prefilterSqlAndParamOrder() {
        repository.search(new float[]{0.1f}, 4, "订单域", List.of("售后", "退货"));

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("doc_id IN (SELECT id FROM rag_document"),
                any(org.springframework.jdbc.core.RowMapper.class), params.capture());
        Object[] args = params.getValue();
        // 参数序：vector, project×2, tagsJoined×2, vector, topK
        assertThat(args).hasSize(7);
        assertThat(args[1]).isEqualTo("订单域");
        assertThat(args[2]).isEqualTo("订单域");
        assertThat(args[3]).isEqualTo("售后,退货");
        assertThat(args[4]).isEqualTo("售后,退货");
        assertThat(args[6]).isEqualTo(4);
    }

    @Test
    void search_filteredByTagsOnly_projectParamsNull() {
        repository.search(new float[]{0.1f}, 4, null, List.of("售后"));

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("tags ??| string_to_array(?::text, ',')"),
                any(org.springframework.jdbc.core.RowMapper.class), params.capture());
        Object[] args = params.getValue();
        assertThat(args[1]).isNull();
        assertThat(args[3]).isEqualTo("售后");
    }

    @Test
    void listDocuments_filtered_sqlAndParams() {
        repository.listDocuments("订单域", "售后");

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("tags ?? ?::text"), any(org.springframework.jdbc.core.RowMapper.class),
                params.capture());
        assertThat(params.getValue()).containsExactly("订单域", "订单域", "售后", "售后");
    }

    @Test
    void updateMeta_fullReplaceSql_returnsAffectedRows() {
        when(jdbc.update(contains("SET project = ?, tags = ?::jsonb"), any(Object[].class)))
                .thenReturn(1);

        int rows = repository.updateMeta(5L, null, List.of("物流"));

        assertThat(rows).isEqualTo(1);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE rag_document"), params.capture());
        Object[] args = params.getValue();
        assertThat(args[0]).isNull();
        assertThat(args[1]).isEqualTo("[\"物流\"]");
        assertThat(args[2]).isEqualTo(5L);
    }

    @Test
    void replaceChunks_doesNotTouchMetaColumns() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.replaceChunks(5L, "a.md", 1, List.of("n1"), vectors(1));

        verify(jdbc, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("project")), any(Object[].class));
    }

    @Test
    void tagsLiteral_emptyBecomesEmptyJsonArray_elseJsonText() {
        assertThat(KbRepository.tagsLiteral(null)).isEqualTo("[]");
        assertThat(KbRepository.tagsLiteral(List.of())).isEqualTo("[]");
        assertThat(KbRepository.tagsLiteral(List.of("售后", "退货"))).isEqualTo("[\"售后\",\"退货\"]");
    }

    @Test
    void parseTags_nullBlankGarbage_defensiveEmpty() {
        assertThat(KbRepository.parseTags(null)).isEmpty();
        assertThat(KbRepository.parseTags("")).isEmpty();
        assertThat(KbRepository.parseTags("   ")).isEmpty();
        assertThat(KbRepository.parseTags("not-json")).isEmpty();
        assertThat(KbRepository.parseTags("[\"a\",\"b\"]")).containsExactly("a", "b");
    }
}
