package com.dj.ai.agentchat.orchestration.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 编排审计表懒建表器（迭代5，T1；范式复刻 ToolSchemaInitializer）。
 *
 * <p><b>无启动 runner</b>：首次编排审计路径（{@link OrchestrationAuditService#record}）
 * 调用 {@link #ensureSchema()}，经 DataSource 取连接 + spring-jdbc {@link ScriptUtils}
 * 执行 classpath {@code db/agent-orchestration-schema.sql}（CREATE TABLE IF NOT EXISTS，
 * 幂等、无外键）。{@link AtomicBoolean} 成功后短路；失败原样抛出且<b>不置位</b>——
 * DB 恢复后下次调用重试（AC-60.3）。
 */
@Slf4j
public class OrchestrationSchemaInitializer {

    static final String SCHEMA_LOCATION = "db/agent-orchestration-schema.sql";

    private final DataSource dataSource;
    private final AtomicBoolean created = new AtomicBoolean(false);

    public OrchestrationSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 确保编排审计表存在；首次执行建表脚本，成功后短路。失败抛 DataAccessException 体系异常
     * （由 {@link OrchestrationAuditService} best-effort 吞掉，仅日志）。
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
                log.info("编排审计表初始化完成（agent_orchestration_run）");
            } catch (CannotGetJdbcConnectionException e) {
                throw e;
            } catch (SQLException e) {
                throw new CannotGetJdbcConnectionException("获取数据库连接失败: " + e.getMessage(), e);
            }
            // ScriptException 属 DataAccessException 体系，自然向上传播由调用方 best-effort 处理
        }
    }
}
