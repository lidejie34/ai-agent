package com.dj.ai.agentchat.rag.dim;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 维度维护仓储（迭代10 追加）：dim_project 受管项目 CRUD + 标签（rag_document.tags
 * distinct 展开派生）查看/改名/删除联动。仅经 {@code ragJdbcTemplate} 访问 PG。
 *
 * <p>JSONB 运算符沿用 KbRepository 的 pgjdbc 教训（冒烟实证）：键存在 {@code ?} 双写
 * {@code ??} 转义；可空/运算符上下文参数显式 {@code ?::text} cast。
 */
public class DimRepository {

    static final String TX_MANAGER = "ragTransactionManager";

    /** 项目列表：子查询带引用文档数（名称字符串松耦合，不做外键）。 */
    private static final String LIST_PROJECTS_SQL = """
            SELECT p.id, p.name, p.remark, p.created_at, p.updated_at,
                   (SELECT count(*) FROM rag_document d WHERE d.project = p.name) AS doc_count
              FROM dim_project p
             ORDER BY p.id
            """;

    /** 标签列表：JSONB 数组展开 distinct + 文档数（标签无独立表，纯派生视图）。 */
    private static final String LIST_TAGS_SQL = """
            SELECT t.name AS name, count(*) AS doc_count
              FROM rag_document d, jsonb_array_elements_text(d.tags) t(name)
             GROUP BY t.name
             ORDER BY t.name
            """;

    /**
     * 标签改名联动：数组内元素 from → to，目标标签已存在时 DISTINCT 去重
     * （tags 是集合语义）。`??` 为 pgjdbc 转义后的 JSONB 键存在运算符。
     */
    private static final String RENAME_TAG_SQL = """
            UPDATE rag_document
               SET tags = (SELECT jsonb_agg(DISTINCT new_v)
                           FROM (SELECT CASE WHEN v #>> '{}' = ? THEN to_jsonb(?::text) ELSE v END AS new_v
                                 FROM jsonb_array_elements(tags) v) sub),
                   updated_at = now()
             WHERE tags ?? ?::text
            """;

    /** 标签删除联动：jsonb `-` 文本运算符按值移除数组元素。 */
    private static final String DELETE_TAG_SQL = """
            UPDATE rag_document
               SET tags = tags - ?::text, updated_at = now()
             WHERE tags ?? ?::text
            """;

    /** 项目改名联动：rag_document.project 按名称引用，逐行更新。 */
    private static final String RENAME_PROJECT_DOCS_SQL =
            "UPDATE rag_document SET project = ?, updated_at = now() WHERE project = ?";

    /** 标签引用计数（`??` 转义 + `?::text` cast，同 KbRepository 教训）。 */
    private static final String COUNT_DOCS_BY_TAG_SQL =
            "SELECT count(*) FROM rag_document WHERE tags ?? ?::text";

    private static final RowMapper<DimProject> PROJECT_ROW_MAPPER = (rs, rowNum) -> new DimProject(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("remark"),
            rs.getLong("doc_count"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static final RowMapper<DimTagView> TAG_ROW_MAPPER = (rs, rowNum) ->
            new DimTagView(rs.getString("name"), rs.getLong("doc_count"));

    private final JdbcTemplate jdbc;

    public DimRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate) {
        this.jdbc = ragJdbcTemplate;
    }

    public List<DimProject> listProjects() {
        return jdbc.query(LIST_PROJECTS_SQL, PROJECT_ROW_MAPPER);
    }

    public Optional<DimProject> findProjectById(long id) {
        return jdbc.query("""
                SELECT p.id, p.name, p.remark, p.created_at, p.updated_at,
                       (SELECT count(*) FROM rag_document d WHERE d.project = p.name) AS doc_count
                  FROM dim_project p WHERE p.id = ?
                """, PROJECT_ROW_MAPPER, id).stream().findFirst();
    }

    public boolean projectExists(String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM dim_project WHERE name = ?",
                Long.class, name);
        return n != null && n > 0;
    }

    /** 新建项目；重名由调用方捕获 DuplicateKeyException 映射 409。 */
    @Transactional(transactionManager = TX_MANAGER)
    public long insertProject(String name, String remark) {
        Long id = jdbc.queryForObject("""
                INSERT INTO dim_project (name, remark) VALUES (?, ?) RETURNING id
                """, Long.class, name, remark);
        if (id == null) {
            throw new IllegalStateException("插入项目未返回 id: " + name);
        }
        return id;
    }

    /** 更新项目行（不含文档联动——联动在 {@link #renameProjectDocs}）。返回受影响行数。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int updateProject(long id, String name, String remark) {
        return jdbc.update("""
                UPDATE dim_project SET name = ?, remark = ?, updated_at = now() WHERE id = ?
                """, name, remark, id);
    }

    /** 项目改名文档联动（同事务由调用方组合）；返回更新行数。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int renameProjectDocs(String oldName, String newName) {
        return jdbc.update(RENAME_PROJECT_DOCS_SQL, newName, oldName);
    }

    public long countDocsByProject(String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_document WHERE project = ?",
                Long.class, name);
        return n == null ? 0 : n;
    }

    /** 删除项目行；返回受影响行数（0=不存在）。引用检查在调用方。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int deleteProject(long id) {
        return jdbc.update("DELETE FROM dim_project WHERE id = ?", id);
    }

    public List<DimTagView> listTags() {
        return jdbc.query(LIST_TAGS_SQL, TAG_ROW_MAPPER);
    }

    public long countDocsByTag(String name) {
        Long n = jdbc.queryForObject(COUNT_DOCS_BY_TAG_SQL, Long.class, name);
        return n == null ? 0 : n;
    }

    /** 标签改名联动；返回受影响文档数（0=无引用，调用方视为无操作）。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int renameTag(String from, String to) {
        return jdbc.update(RENAME_TAG_SQL, from, to, from);
    }

    /** 标签删除联动；返回受影响文档数。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int deleteTag(String name) {
        return jdbc.update(DELETE_TAG_SQL, name, name);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
