package com.dj.ai.agentchat.dim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 受管项目仓储（迭代10 迁移）：dim_project 表 CRUD，仅经主 {@link JdbcTemplate}
 * 访问 <b>MySQL</b>（自 rag PG 库迁来——RAG 关闭也能维护项目，且供后续工具调度复用）。
 *
 * <p>只管 dim_project 单表；文档引用计数/改名联动在 rag 侧 {@code DimRagRepository}，
 * 由 {@link DimProjectService} 编排合并。每方法单语句，自动提交即原子，无需显式事务。
 */
public class DimProjectRepository {

    /** 项目列表：不带引用计数（跨库，由服务层合并 PG 分组计数），docCount 先置 0。 */
    private static final String LIST_PROJECTS_SQL = """
            SELECT id, name, remark, created_at, updated_at
              FROM dim_project
             ORDER BY id
            """;

    private static final RowMapper<DimProject> PROJECT_ROW_MAPPER = (rs, rowNum) -> new DimProject(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("remark"),
            0L,
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public DimProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    public List<DimProject> listProjects() {
        return jdbc.query(LIST_PROJECTS_SQL, PROJECT_ROW_MAPPER);
    }

    public Optional<DimProject> findProjectById(long id) {
        return jdbc.query("""
                SELECT id, name, remark, created_at, updated_at
                  FROM dim_project WHERE id = ?
                """, PROJECT_ROW_MAPPER, id).stream().findFirst();
    }

    public boolean projectExists(String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM dim_project WHERE name = ?",
                Long.class, name);
        return n != null && n > 0;
    }

    /**
     * 新建项目；重名由调用方捕获 DuplicateKeyException 映射 409。
     * MySQL 无 RETURNING——经 {@link GeneratedKeyHolder} 取回自增 id；显式声明返回列
     * {@code {"id"}}（冒烟实证：不指定列时驱动可返回整行，getKey() 多列报错）。
     */
    public long insertProject(String name, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dim_project (name, remark) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入项目未返回自增 id: " + name);
        }
        return key.longValue();
    }

    /** 更新项目行（不含文档联动——跨库联动在 {@link DimProjectService}）。返回受影响行数。 */
    public int updateProject(long id, String name, String remark) {
        return jdbc.update("UPDATE dim_project SET name = ?, remark = ? WHERE id = ?",
                name, remark, id);
    }

    /** 删除项目行；返回受影响行数（0=不存在）。引用检查在调用方。 */
    public int deleteProject(long id) {
        return jdbc.update("DELETE FROM dim_project WHERE id = ?", id);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
