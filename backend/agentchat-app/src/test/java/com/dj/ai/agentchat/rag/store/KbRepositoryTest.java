package com.dj.ai.agentchat.rag.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5：KbRepository SQL/事务单测（mock JdbcTemplate）——同名覆盖顺序、READY/FAILED 参数、
 * 批量插片段带 ?::vector、cosine 检索 SQL/参数、重建事务、RowMapper 映射、向量字面量格式。
 * 真实 PG 行为（HNSW/cascade/RETURNING）由 SMOKE-RAG 覆盖。
 */
class KbRepositoryTest {

    private JdbcTemplate jdbc;
    private KbRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new KbRepository(jdbc);
    }

    private List<float[]> vectors(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new float[]{0.1f * i, -0.2f})
                .toList();
    }

    @Test
    void saveReady_deletesSameName_thenInsertsReadyDoc_andBatchChunks_inOrder() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);

        long id = repository.saveReady("差旅制度.md", 1234, "原文", "hashaa",
                List.of("片1", "片2"), vectors(2));

        assertThat(id).isEqualTo(7L);

        // 先按文件名删旧（同名覆盖，单事务内）
        verify(jdbc).update(contains("DELETE FROM rag_document WHERE file_name"),
                eq("差旅制度.md"));

        // 文档插入参数：file_name,size,content,hash,chunk_count=2,READY,null
        ArgumentCaptor<Object[]> docArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("INSERT INTO rag_document"), eq(Long.class),
                docArgs.capture());
        Object[] args = docArgs.getValue();
        assertThat(args).containsExactly("差旅制度.md", 1234, "原文", "hashaa", 2,
                RagDocument.STATUS_READY, null);

        // 片段批量：2 行，列序 doc_id,index,file_name,content,vector literal
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List<Object[]>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(contains("INSERT INTO rag_chunk"), batchCaptor.capture());
        List<Object[]> rows = batchCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(7L, 0, "差旅制度.md", "片1", "[0.0,-0.2]");
        assertThat(rows.get(1)).containsExactly(7L, 1, "差旅制度.md", "片2", "[0.1,-0.2]");
    }

    @Test
    void saveReady_chunkVectorCountMismatch_throwsWithoutAnySql() {
        assertThatThrownBy(() -> repository.saveReady("a.md", 1, "c", "h",
                List.of("x"), vectors(2)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void saveFailed_replacesSameName_andStoresReadyFailedRowWithError() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(8L);

        long id = repository.saveFailed("坏文件.txt", 10, "原文留存", "h1",
                "Embedding 连接超时");

        assertThat(id).isEqualTo(8L);
        verify(jdbc).update(contains("DELETE FROM rag_document WHERE file_name"),
                eq("坏文件.txt"));
        ArgumentCaptor<Object[]> docArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("INSERT INTO rag_document"), eq(Long.class),
                docArgs.capture());
        Object[] args = docArgs.getValue();
        assertThat(args[0]).isEqualTo("坏文件.txt");
        assertThat(args[4]).isEqualTo(0);
        assertThat(args[5]).isEqualTo(RagDocument.STATUS_FAILED);
        assertThat(args[6]).isEqualTo("Embedding 连接超时");
    }

    @Test
    void deleteById_returnsTrueWhenRowAffected_cascadeByFk() {
        when(jdbc.update(contains("DELETE FROM rag_document WHERE id"), eq(3L))).thenReturn(1);
        assertThat(repository.deleteById(3L)).isTrue();
    }

    @Test
    void deleteById_returnsFalseWhenMissing() {
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(0);
        assertThat(repository.deleteById(99L)).isFalse();
    }

    @Test
    void replaceChunks_deletesOldChunks_insertsNew_marksReady() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.replaceChunks(5L, "a.md", 2, List.of("n1", "n2"), vectors(2));

        verify(jdbc).update(contains("DELETE FROM rag_chunk WHERE doc_id"), eq(5L));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List<Object[]>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(contains("INSERT INTO rag_chunk"), batchCaptor.capture());
        assertThat(batchCaptor.getValue()).hasSize(2);
        verify(jdbc).update(contains("UPDATE rag_document"),
                eq(2), eq(RagDocument.STATUS_READY), eq(5L));
    }

    @Test
    void replaceChunks_countMismatch_throws() {
        assertThatThrownBy(() -> repository.replaceChunks(1L, "a.md", 3,
                List.of("a"), vectors(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findById_mapsAllColumnsIncludingContent() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        when(rs.getLong("id")).thenReturn(11L);
        when(rs.getString("file_name")).thenReturn("制度.md");
        when(rs.getInt("size_bytes")).thenReturn(4096);
        when(rs.getString("content")).thenReturn("正文全文");
        when(rs.getString("content_hash")).thenReturn("sha1");
        when(rs.getInt("chunk_count")).thenReturn(4);
        when(rs.getString("status")).thenReturn("READY");
        when(rs.getString("error")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(now);
        when(rs.getTimestamp("updated_at")).thenReturn(now);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<RagDocument> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        Optional<RagDocument> doc = repository.findById(11L);

        assertThat(doc).isPresent();
        assertThat(doc.get().id()).isEqualTo(11L);
        assertThat(doc.get().content()).isEqualTo("正文全文");
        assertThat(doc.get().createdAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 10, 0));
    }

    @Test
    void findById_absent_returnsEmpty() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        assertThat(repository.findById(404L)).isEmpty();
    }

    @Test
    void listDocuments_queryOrdersByIdDesc() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        repository.listDocuments();
        verify(jdbc).query(contains("ORDER BY id DESC"), any(RowMapper.class));
    }

    @Test
    void search_usesCosineDistanceTwiceAndLimit_passesLiteralTwiceAndTopK() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new RagChunkView("制度.md", "片段", 0.77)));

        float[] q = new float[]{0.5f, 0.25f};
        List<RagChunkView> hits = repository.search(q, 4);

        assertThat(hits).hasSize(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        // 计分列 + HNSW 排序列各一个 <=>
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(sql, "<=>"))
                .isEqualTo(2);
        assertThat(sql).contains("LIMIT ?");
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(sql, "?"))
                .isEqualTo(3);
    }

    @Test
    void search_passesVectorLiteralTwiceThenTopK() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search(new float[]{0.1f}, 6);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo("[0.1]");
        assertThat(args[1]).isEqualTo("[0.1]");
        assertThat(args[2]).isEqualTo(6);
    }

    @Test
    void findByFileName_queriesByName() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        repository.findByFileName("x.md");
        verify(jdbc).query(contains("WHERE file_name"), any(RowMapper.class), eq("x.md"));
    }

    @Test
    void counts_delegateToCountStar() {
        when(jdbc.queryForObject(contains("rag_document"), eq(Long.class)))
                .thenReturn(3L);
        when(jdbc.queryForObject(contains("rag_chunk"), eq(Long.class)))
                .thenReturn(27L);
        assertThat(repository.countDocuments()).isEqualTo(3L);
        assertThat(repository.countChunks()).isEqualTo(27L);
    }

    @Test
    void countNull_safeToZero() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
        assertThat(repository.countDocuments()).isZero();
    }

    @Test
    void vectorLiteral_pgvectorTextFormat_dotDecimal_noSpaces() {
        String literal = KbRepository.formatVectorLiteral(new float[]{0.1f, -0.2f, 12.5f});
        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal).contains("0.1", "-0.2", "12.5");
        assertThat(literal).doesNotContain(" ");
    }

    @Test
    void vectorLiteral_1024dimensions_parsableShape() {
        String literal = KbRepository.formatVectorLiteral(new float[1024]);
        assertThat(literal.substring(1, literal.length() - 1).split(",")).hasSize(1024);
    }

    @Test
    void writeMethods_areTransactionalOnRagTransactionManager() throws Exception {
        for (String m : List.of("saveReady", "saveFailed", "replaceChunks")) {
            Transactional t = switch (m) {
                case "saveReady" -> KbRepository.class.getMethod("saveReady",
                        String.class, int.class, String.class, String.class, List.class, List.class)
                        .getAnnotation(Transactional.class);
                case "saveFailed" -> KbRepository.class.getMethod("saveFailed",
                        String.class, int.class, String.class, String.class, String.class)
                        .getAnnotation(Transactional.class);
                default -> KbRepository.class.getMethod("replaceChunks",
                        long.class, String.class, int.class, List.class, List.class)
                        .getAnnotation(Transactional.class);
            };
            assertThat(t).as(m).isNotNull();
            assertThat(t.transactionManager()).isEqualTo(KbRepository.TX_MANAGER);
        }
    }
}
