package com.dj.ai.agentchat.rag.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * pgvector 知识库仓储（迭代6）：仅经 {@code ragJdbcTemplate} 访问 PG，绝不触碰 MySQL。
 *
 * <p>写路径全部在 {@code ragTransactionManager} 事务内：同名覆盖 = 单事务内先按
 * file_name 删旧（FK ON DELETE CASCADE 连带旧片段）再插新，保证旧片段不残留（F6）；
 * 失败文档也落 FAILED 行（含原文，供 reindex 重试）。检索走余弦距离 {@code <=>} +
 * HNSW 索引，SQL 不加 WHERE 阈值，由应用层按 min-score 过滤便于调参。
 */
@Slf4j
public class KbRepository {

    static final String TX_MANAGER = "ragTransactionManager";

    private static final String INSERT_DOC_SQL = """
            INSERT INTO rag_document
                (file_name, size_bytes, content, content_hash, chunk_count, status, error)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String INSERT_CHUNK_SQL = """
            INSERT INTO rag_chunk (doc_id, chunk_index, file_name, content, embedding)
            VALUES (?, ?, ?, ?, ?::vector)
            """;

    private static final RowMapper<RagDocument> DOC_ROW_MAPPER = (rs, rowNum) -> new RagDocument(
            rs.getLong("id"),
            rs.getString("file_name"),
            rs.getInt("size_bytes"),
            rs.getString("content"),
            rs.getString("content_hash"),
            rs.getInt("chunk_count"),
            rs.getString("status"),
            rs.getString("error"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static final RowMapper<RagChunkView> CHUNK_VIEW_ROW_MAPPER = (rs, rowNum) ->
            new RagChunkView(rs.getString("file_name"), rs.getString("content"),
                    rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    public KbRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate) {
        this.jdbc = ragJdbcTemplate;
    }

    /**
     * 同名覆盖 + 就绪落库（单事务）：先按文件名删旧（级联旧片段），再插 READY 文档与全部片段。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveReady(String fileName, int sizeBytes, String content, String contentHash,
                          List<String> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("片段数与向量数不一致: " + chunks.size() + " vs " + vectors.size());
        }
        deleteByFileName(fileName);
        Long id = jdbc.queryForObject(INSERT_DOC_SQL, Long.class,
                fileName, sizeBytes, content, contentHash, chunks.size(),
                RagDocument.STATUS_READY, null);
        if (id == null) {
            throw new IllegalStateException("插入文档未返回 id: " + fileName);
        }
        insertChunks(id, fileName, chunks, vectors);
        return id;
    }

    /**
     * 失败落库（单事务）：覆盖同名旧行后写 FAILED 文档（保留原文，错误信息截断 1000 字符内）。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveFailed(String fileName, int sizeBytes, String content, String contentHash,
                           String error) {
        deleteByFileName(fileName);
        String safeError = error == null ? "未知错误"
                : error.substring(0, Math.min(error.length(), 900));
        return jdbc.queryForObject(INSERT_DOC_SQL, Long.class,
                fileName, sizeBytes, content, contentHash, 0,
                RagDocument.STATUS_FAILED, safeError);
    }

    /** 删除文档（FK 级联片段）；返回受影响行数（0=不存在）。 */
    @Transactional(transactionManager = TX_MANAGER)
    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM rag_document WHERE id = ?", id) > 0;
    }

    /**
     * 重建索引（单事务）：清空旧片段 → 写新片段 → 文档转 READY。
     * 调用方须先自行完成切片+embedding，本方法不做外部调用。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public void replaceChunks(long docId, String fileName, int chunkCount,
                              List<String> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size() || chunks.size() != chunkCount) {
            throw new IllegalArgumentException("重建片段数量不一致");
        }
        jdbc.update("DELETE FROM rag_chunk WHERE doc_id = ?", docId);
        insertChunks(docId, fileName, chunks, vectors);
        jdbc.update("""
                UPDATE rag_document
                   SET chunk_count = ?, status = ?, error = NULL, updated_at = now()
                 WHERE id = ?
                """, chunkCount, RagDocument.STATUS_READY, docId);
    }

    /** 置文档失败态（reindex 失败用，保留原文与旧片段）。 */
    @Transactional(transactionManager = TX_MANAGER)
    public void markFailed(long docId, String error) {
        String safeError = error == null ? "未知错误"
                : error.substring(0, Math.min(error.length(), 900));
        jdbc.update("""
                UPDATE rag_document
                   SET status = ?, error = ?, updated_at = now()
                 WHERE id = ?
                """, RagDocument.STATUS_FAILED, safeError, docId);
    }

    /** 文档详情（含原文，reindex 用）；不存在返回 empty。 */
    public Optional<RagDocument> findById(long id) {
        return jdbc.query("SELECT * FROM rag_document WHERE id = ?", DOC_ROW_MAPPER, id)
                .stream().findFirst();
    }

    /** 按文件名查文档（含原文）。 */
    public Optional<RagDocument> findByFileName(String fileName) {
        return jdbc.query("SELECT * FROM rag_document WHERE file_name = ?",
                DOC_ROW_MAPPER, fileName).stream().findFirst();
    }

    /** 文档列表（不含原文，按 id 倒序——新上传在前）。 */
    public List<RagDocument> listDocuments() {
        return jdbc.query("""
                SELECT id, file_name, size_bytes, NULL AS content, content_hash,
                       chunk_count, status, error, created_at, updated_at
                  FROM rag_document
                 ORDER BY id DESC
                """, DOC_ROW_MAPPER);
    }

    /**
     * 余弦相似度检索：{@code <=>} 为 cosine distance，{@code 1-distance} 即相似度。
     * 向量字面量传两次（SELECT 计分 + ORDER BY 走 HNSW）；阈值过滤在应用层。
     */
    public List<RagChunkView> search(float[] queryVector, int topK) {
        String vectorLiteral = formatVectorLiteral(queryVector);
        String sql = """
                SELECT file_name, content, 1 - (embedding <=> ?::vector) AS score
                  FROM rag_chunk
                 ORDER BY embedding <=> ?::vector
                 LIMIT ?
                """;
        return jdbc.query(sql, CHUNK_VIEW_ROW_MAPPER, vectorLiteral, vectorLiteral, topK);
    }

    public long countDocuments() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_document", Long.class);
        return n == null ? 0 : n;
    }

    public long countChunks() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_chunk", Long.class);
        return n == null ? 0 : n;
    }

    private int deleteByFileName(String fileName) {
        return jdbc.update("DELETE FROM rag_document WHERE file_name = ?", fileName);
    }

    private void insertChunks(long docId, String fileName, List<String> chunks,
                              List<float[]> vectors) {
        java.util.List<Object[]> batch = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            batch.add(new Object[]{
                    docId, i, fileName, chunks.get(i), formatVectorLiteral(vectors.get(i))});
        }
        jdbc.batchUpdate(INSERT_CHUNK_SQL, batch);
    }

    /**
     * 格式化为 pgvector 文本格式 {@code [0.012,-0.045,...]}（Float.toString 恒为点号小数，
     * 1024 维约 12-16KB，走 ?::vector 文本协议）。
     */
    static String formatVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%s", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
