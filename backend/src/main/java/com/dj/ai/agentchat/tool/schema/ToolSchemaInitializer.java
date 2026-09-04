package com.dj.ai.agentchat.tool.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具表懒建表器（插入迭代 G，范式复刻 ChatMemorySchemaInitializer）。
 *
 * <p>首次工具路径（ToolRegistry 装载 / ToolSeeder 种子 / 管理端写操作）调用
 * {@link #ensureSchema()}：经 DataSource 取连接 + spring-jdbc {@link ScriptUtils}
 * 执行 classpath {@code db/agent-tool-schema.sql}（两条 CREATE TABLE IF NOT EXISTS，
 * 幂等）。{@link AtomicBoolean} 成功后短路；失败（连接拒绝/ScriptException，同属
 * DataAccessException 体系）原样抛出且<b>不置位</b>——DB 恢复后下次调用重试。
 */
@Slf4j
public class ToolSchemaInitializer {

    static final String SCHEMA_LOCATION = "db/agent-tool-schema.sql";

    private final DataSource dataSource;
    private final AtomicBoolean created = new AtomicBoolean(false);

    public ToolSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 确保工具两表存在；首次执行建表脚本，成功后短路。失败抛 DataAccessException 体系异常
     * （连接失败为 {@link CannotGetJdbcConnectionException}，脚本失败为 ScriptException）。
     */
    public void ensureSchema() {
        if (created.get()) {
            return;
        }
        synchronized (this) {
            if (created.get()) {
                return;
            }
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_LOCATION));
                created.set(true);
                log.info("工具表初始化完成（agent_tool / agent_tool_call_log）");
            } catch (CannotGetJdbcConnectionException e) {
                // 连接获取失败（Hikari 懒连接，DB 不可达时首借连接抛此异常）：直接传播
                throw e;
            } catch (SQLException e) {
                throw new CannotGetJdbcConnectionException("获取数据库连接失败: " + e.getMessage(), e);
            }
            // ScriptException 属 DataAccessException 体系，自然向上传播由调用方 best-effort 处理
        }
    }
}
