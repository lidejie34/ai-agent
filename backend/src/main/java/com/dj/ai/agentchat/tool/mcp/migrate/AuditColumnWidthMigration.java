package com.dj.ai.agentchat.tool.mcp.migrate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 审计表 {@code agent_tool_call_log.tool_name} 列宽幂等迁移（插入迭代4，T1，AC-19）。
 *
 * <p>背景：迭代 G 建表为 VARCHAR(64)；MCP 工具审计存完整暴露名（{@code <server>_<tool>}，
 * 规整后 ≤64 传给模型，审计列按计划存全名），列宽放宽至 {@value #TARGET_WIDTH}。
 * 新库由 {@code db/agent-tool-schema.sql} 直接建为 128；既有库由本 Runner 启动期
 * best-effort 迁移：
 * <ol>
 *   <li>查 {@code information_schema.COLUMNS} 当前列宽；</li>
 *   <li>表/列不存在（DB 不可达、尚未懒建表）→ 静默跳过（后续懒建表以新 SQL 直接建为 128）；</li>
 *   <li>列宽 ≥128 → 不执行（幂等，重复启动无副作用）；</li>
 *   <li>列宽 &lt;128 → {@code ALTER TABLE ... MODIFY COLUMN ... VARCHAR(128) NOT NULL COMMENT ...}；</li>
 *   <li>任何异常（无 ALTER 权限等）→ WARN 不阻断启动；审计是 best-effort 链路，
 *       超长名另由调用侧 64 字符规整兜底（传给模型的名字 ≤64，即便列宽仍 64 也能容下）。</li>
 * </ol>
 */
@Slf4j
public class AuditColumnWidthMigration implements ApplicationRunner {

    static final String TABLE_NAME = "agent_tool_call_log";
    static final String COLUMN_NAME = "tool_name";
    static final int TARGET_WIDTH = 128;

    private static final String WIDTH_QUERY = """
            SELECT CHARACTER_MAXIMUM_LENGTH
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """;

    private static final String ALTER_SQL = "ALTER TABLE " + TABLE_NAME
            + " MODIFY COLUMN " + COLUMN_NAME + " VARCHAR(" + TARGET_WIDTH
            + ") NOT NULL COMMENT '工具名字符串留存（MCP 为 <server>_<tool> 全名；工具行删除后审计保留，无外键）'";

    private final JdbcTemplate jdbcTemplate;

    public AuditColumnWidthMigration(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 测试入口：直接注入 mock JdbcTemplate。 */
    AuditColumnWidthMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /**
     * best-effort 列宽迁移；表/列缺失跳过、已够宽跳过、异常 WARN 不抛出。
     *
     * @return true=执行了 ALTER；false=跳过/失败
     */
    public boolean migrate() {
        try {
            Integer currentWidth = queryColumnWidth();
            if (currentWidth == null) {
                // 表不存在（DB 不可达/未懒建表）：静默跳过——下次懒建表按新 SQL 直接 128
                log.debug("审计列宽迁移跳过：表/列不存在（DB 不可达或未建表）: {}.{}",
                        TABLE_NAME, COLUMN_NAME);
                return false;
            }
            if (currentWidth >= TARGET_WIDTH) {
                log.info("审计列宽无需迁移：{}.{} 当前 {} ≥ {}",
                        TABLE_NAME, COLUMN_NAME, currentWidth, TARGET_WIDTH);
                return false;
            }
            jdbcTemplate.execute(ALTER_SQL);
            log.info("审计列宽迁移完成: {}.{} VARCHAR({}) -> VARCHAR({})",
                    TABLE_NAME, COLUMN_NAME, currentWidth, TARGET_WIDTH);
            return true;
        } catch (Throwable t) {
            // 无 ALTER 权限/DB 抖动等：WARN 不阻断（审计 best-effort；64 字符规整名兜底可写）
            log.warn("审计列宽迁移失败（不阻断启动；超长 MCP 工具名由调用侧 64 字符规整兜底）: {}",
                    t.getMessage());
            return false;
        }
    }

    /** 查当前列宽；表/列不存在返回 null。 */
    private Integer queryColumnWidth() {
        List<Integer> rows = jdbcTemplate.query(
                WIDTH_QUERY,
                (rs, rowNum) -> {
                    int value = rs.getInt(1);
                    return rs.wasNull() ? null : value;
                },
                TABLE_NAME, COLUMN_NAME);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
