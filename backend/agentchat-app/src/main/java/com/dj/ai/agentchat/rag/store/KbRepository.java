package com.dj.ai.agentchat.rag.store;

import com.alibaba.fastjson2.JSON;
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
 *
 * <p>迭代10 维度元数据：文档行带 project（可空）/tags（JSONB）；检索与列表的过滤变体
 * 在 rag_document 维 prefilter（{@code doc_id IN (SELECT ...)}），<b>无过滤参数时走
 * 迭代6 原始 SQL 文本</b>（逐字节回归）。tags OR 语义经 {@code ?| string_to_array(?,',')}，
 * 元素逗号禁令由 {@code KbMetaValidator} 保证。
 */
@Slf4j
public class KbRepository {

    static final String TX_MANAGER = "ragTransactionManager";

    private static final String INSERT_DOC_SQL = """
            INSERT INTO rag_document
                (file_name, size_bytes, content, content_hash, chunk_count, status, error, project, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            RETURNING id
            """;

    private static final String INSERT_CHUNK_SQL = """
            INSERT INTO rag_chunk (doc_id, chunk_index, file_name, content, embedding)
            VALUES (?, ?, ?, ?, ?::vector)
            """;

    /** 迭代6 原始检索 SQL（无维度过滤）——无过滤参数时必须逐字节走此文本。 */
    private static final String SEARCH_SQL_UNFILTERED = """
            SELECT file_name, content, 1 - (embedding <=> ?::vector) AS score
              FROM rag_chunk
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """;

    /**
     * 迭代10 过滤检索 SQL：doc 维 prefilter（projects 集合任一命中；tags `?|` 任一命中）。
     * 迭代11：project 等值升级为 projects `= ANY(string_to_array(?))`——项目多选 OR 语义，
     * 与标签同套逗号拼接传参（元素经 KbMetaValidator 禁逗号，安全）。
     * 注意 `??|` 双写：pgjdbc 把单个 ? 解析为占位符，双写转义为字面操作符
     * （驱动层转换，GIN 索引语义不变）——冒烟实证单写会 500。
     * 所有可空参数显式 `?::text`：Java 传 null 时驱动按 unspecified
     * OID 上报，PG 在 `? IS NULL`/操作符上下文不做类型推断（could not determine
     * data type of parameter）——冒烟实证。
     */
    private static final String SEARCH_SQL_FILTERED = """
            SELECT file_name, content, 1 - (embedding <=> ?::vector) AS score
              FROM rag_chunk
             WHERE doc_id IN (SELECT id FROM rag_document
                               WHERE (?::text IS NULL OR project = ANY(string_to_array(?::text, ',')))
                                 AND (?::text IS NULL OR tags ??| string_to_array(?::text, ',')))
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """;

    /** 迭代6 原始列表 SQL（无过滤）。 */
    private static final String LIST_SQL_UNFILTERED = """
            SELECT id, file_name, size_bytes, NULL AS content, content_hash,
                   chunk_count, status, error, created_at, updated_at, project, tags
              FROM rag_document
             ORDER BY id DESC
            """;

    /** 迭代10 过滤列表 SQL：project 等值 + 单标签包含（`?` 操作符双写转义，同上），组合 AND。 */
    private static final String LIST_SQL_FILTERED = """
            SELECT id, file_name, size_bytes, NULL AS content, content_hash,
                   chunk_count, status, error, created_at, updated_at, project, tags
              FROM rag_document
             WHERE (?::varchar IS NULL OR project = ?::varchar)
               AND (?::text IS NULL OR tags ?? ?::text)
             ORDER BY id DESC
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
            toLocalDateTime(rs.getTimestamp("updated_at")),
            rs.getString("project"),
            parseTags(rs.getString("tags")));

    private static final RowMapper<RagChunkView> CHUNK_VIEW_ROW_MAPPER = (rs, rowNum) ->
            new RagChunkView(rs.getString("file_name"), rs.getString("content"),
                    rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    public KbRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate) {
        this.jdbc = ragJdbcTemplate;
    }

    /**
     * 同名覆盖 + 就绪落库（单事务）：先按文件名删旧（级联旧片段），再插 READY 文档与全部片段。
     * 维度元数据（迭代10）：同名覆盖以新上传的 project/tags 为准。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveReady(String fileName, int sizeBytes, String content, String contentHash,
                          List<String> chunks, List<float[]> vectors,
                          String project, List<String> tags) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("片段数与向量数不一致: " + chunks.size() + " vs " + vectors.size());
        }
        deleteByFileName(fileName);
        Long id = jdbc.queryForObject(INSERT_DOC_SQL, Long.class,
                fileName, sizeBytes, content, contentHash, chunks.size(),
                RagDocument.STATUS_READY, null, project, tagsLiteral(tags));
        if (id == null) {
            throw new IllegalStateException("插入文档未返回 id: " + fileName);
        }
        insertChunks(id, fileName, chunks, vectors);
        return id;
    }

    /** 迭代6 兼容签名：无维度元数据（project=null、tags=空）。 */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveReady(String fileName, int sizeBytes, String content, String contentHash,
                          List<String> chunks, List<float[]> vectors) {
        return saveReady(fileName, sizeBytes, content, contentHash, chunks, vectors,
                null, List.of());
    }

    /**
     * 失败落库（单事务）：覆盖同名旧行后写 FAILED 文档（保留原文，错误信息截断 1000 字符内）。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveFailed(String fileName, int sizeBytes, String content, String contentHash,
                           String error, String project, List<String> tags) {
        deleteByFileName(fileName);
        String safeError = error == null ? "未知错误"
                : error.substring(0, Math.min(error.length(), 900));
        return jdbc.queryForObject(INSERT_DOC_SQL, Long.class,
                fileName, sizeBytes, content, contentHash, 0,
                RagDocument.STATUS_FAILED, safeError, project, tagsLiteral(tags));
    }

    /** 迭代6 兼容签名：无维度元数据。 */
    @Transactional(transactionManager = TX_MANAGER)
    public long saveFailed(String fileName, int sizeBytes, String content, String contentHash,
                           String error) {
        return saveFailed(fileName, sizeBytes, content, contentHash, error, null, List.of());
    }

    /** 删除文档（FK 级联片段）；返回受影响行数（0=不存在）。 */
    @Transactional(transactionManager = TX_MANAGER)
    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM rag_document WHERE id = ?", id) > 0;
    }

    /**
     * 重建索引（单事务）：清空旧片段 → 写新片段 → 文档转 READY。
     * 调用方须先自行完成切片+embedding，本方法不做外部调用。
     * 迭代10：不触碰 project/tags 列——reindex 保留原维度元数据。
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

    /**
     * 全量替换维度元数据（迭代10 PATCH 语义）：project 可 null=清除归属，tags 空=清空标签。
     * 返回受影响行数（0=不存在，调用方映射 404）。
     */
    @Transactional(transactionManager = TX_MANAGER)
    public int updateMeta(long id, String project, List<String> tags) {
        return jdbc.update("""
                UPDATE rag_document
                   SET project = ?, tags = ?::jsonb, updated_at = now()
                 WHERE id = ?
                """, project, tagsLiteral(tags), id);
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

    /** 文档列表（不含原文，按 id 倒序——新上传在前）；无过滤走迭代6 原始 SQL。 */
    public List<RagDocument> listDocuments() {
        return jdbc.query(LIST_SQL_UNFILTERED, DOC_ROW_MAPPER);
    }

    /**
     * 文档列表过滤变体（迭代10）：project 等值 + 单标签包含，组合 AND；
     * 两参全空时等价 {@link #listDocuments()}（但走过滤 SQL 模板）。
     */
    public List<RagDocument> listDocuments(String project, String tag) {
        return jdbc.query(LIST_SQL_FILTERED, DOC_ROW_MAPPER, project, project, tag, tag);
    }

    /**
     * 余弦相似度检索：{@code <=>} 为 cosine distance，{@code 1-distance} 即相似度。
     * 向量字面量传两次（SELECT 计分 + ORDER BY 走 HNSW）；阈值过滤在应用层。
     * 无维度过滤（迭代6 签名）——走原始 SQL 文本。
     */
    public List<RagChunkView> search(float[] queryVector, int topK) {
        String vectorLiteral = formatVectorLiteral(queryVector);
        return jdbc.query(SEARCH_SQL_UNFILTERED, CHUNK_VIEW_ROW_MAPPER,
                vectorLiteral, vectorLiteral, topK);
    }

    /**
     * 维度过滤检索（迭代10；迭代11 项目多选）：projects/tags 任一非空走过滤 SQL
     * （doc 维 prefilter，两维各自 OR、之间 AND）；两者全空委托
     * {@link #search(float[], int)}（原始 SQL 逐字节回归）。
     */
    public List<RagChunkView> search(float[] queryVector, int topK,
                                     List<String> projects, List<String> tags) {
        if ((projects == null || projects.isEmpty()) && (tags == null || tags.isEmpty())) {
            return search(queryVector, topK);
        }
        String vectorLiteral = formatVectorLiteral(queryVector);
        String projectsJoined = projects == null || projects.isEmpty() ? null : String.join(",", projects);
        String tagsJoined = tags == null || tags.isEmpty() ? null : String.join(",", tags);
        return jdbc.query(SEARCH_SQL_FILTERED, CHUNK_VIEW_ROW_MAPPER,
                vectorLiteral, projectsJoined, projectsJoined, tagsJoined, tagsJoined, vectorLiteral, topK);
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

    /** tags → JSON 数组文本（?::jsonb 参数）；空 → "[]"（DDL NOT NULL DEFAULT '[]'）。 */
    static String tagsLiteral(List<String> tags) {
        return tags == null || tags.isEmpty() ? "[]" : JSON.toJSONString(tags);
    }

    /** JSONB 文本 → 标签列表；NULL/空白/解析失败防御为空列表（存量行/异常都不得炸查询）。 */
    static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = JSON.parseArray(json, String.class);
            return tags == null ? List.of() : List.copyOf(tags);
        } catch (Exception e) {
            log.warn("rag_document.tags JSONB 解析失败，按空标签处理: {}", e.getMessage());
            return List.of();
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
