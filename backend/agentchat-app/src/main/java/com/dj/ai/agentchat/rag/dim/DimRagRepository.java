package com.dj.ai.agentchat.rag.dim;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 维度维护的 rag 侧仓储（迭代10；原 DimRepository 瘦身——dim_project 表迁至 MySQL 后，
 * 本类只剩与 rag_document 强绑定的操作）：标签（rag_document.tags distinct 展开派生）
 * 查看/改名/删除联动 + 项目引用计数/项目改名文档联动。仅经 {@code ragJdbcTemplate} 访问 PG。
 *
 * <p>JSONB 运算符沿用 KbRepository 的 pgjdbc 教训（冒烟实证）：键存在 {@code ?} 双写
 * {@code ??} 转义；可空/运算符上下文参数显式 {@code ?::text} cast。
 */
public class DimRagRepository {

    static final String TX_MANAGER = "ragTransactionManager";

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

    /** 各项目引用文档数分组统计（服务层合并进 MySQL 侧项目列表，跨库应用层 join）。 */
    private static final String COUNT_DOCS_GROUP_BY_PROJECT_SQL = """
            SELECT project, count(*) AS doc_count
              FROM rag_document
             WHERE project IS NOT NULL
             GROUP BY project
            """;

    private static final RowMapper<DimTagView> TAG_ROW_MAPPER = (rs, rowNum) ->
            new DimTagView(rs.getString("name"), rs.getLong("doc_count"));

    private final JdbcTemplate jdbc;

    public DimRagRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate) {
        this.jdbc = ragJdbcTemplate;
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

    /** 项目改名文档联动（跨库编排由 DimProjectService 负责）；返回更新行数。 */
    @Transactional(transactionManager = TX_MANAGER)
    public int renameProjectDocs(String oldName, String newName) {
        return jdbc.update(RENAME_PROJECT_DOCS_SQL, newName, oldName);
    }

    public long countDocsByProject(String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_document WHERE project = ?",
                Long.class, name);
        return n == null ? 0 : n;
    }

    /** 项目名 → 引用文档数（无引用项目不出现）。 */
    public Map<String, Long> countDocsGroupByProject() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(COUNT_DOCS_GROUP_BY_PROJECT_SQL, rs -> {
            counts.put(rs.getString("project"), rs.getLong("doc_count"));
        });
        return counts;
    }
}
