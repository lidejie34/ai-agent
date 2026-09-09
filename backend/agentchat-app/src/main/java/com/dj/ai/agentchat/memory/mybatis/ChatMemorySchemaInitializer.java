package com.dj.ai.agentchat.memory.mybatis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆表懒建表器（迭代3，实证 5/8）。
 *
 * <p>首次记忆路径（MybatisChatMemory 各公有方法入口）调用 {@link #ensureSchema()}：
 * 经 DataSource 取连接 + spring-jdbc {@link ScriptUtils} 执行 classpath
 * {@code db/chat-memory-schema.sql}（两条 CREATE TABLE IF NOT EXISTS，幂等）。
 * {@link AtomicBoolean} 成功后短路；失败（连接拒绝/ScriptException，同属 DataAccessException
 * 体系）原样抛出且<b>不置位</b>——DB 恢复后下次调用重试。
 */
@Slf4j
public class ChatMemorySchemaInitializer {

    static final String SCHEMA_LOCATION = "db/chat-memory-schema.sql";

    private final DataSource dataSource;
    private final AtomicBoolean created = new AtomicBoolean(false);

    public ChatMemorySchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 确保记忆表存在；首次执行建表脚本，成功后短路。失败抛 DataAccessException 体系异常
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
                log.info("会话记忆表初始化完成（chat_session / chat_message）");
            } catch (CannotGetJdbcConnectionException e) {
                // 连接获取失败（Hikari 懒连接，DB 不可达时首借连接抛此异常）：直接传播
                throw e;
            } catch (SQLException e) {
                throw new CannotGetJdbcConnectionException("获取数据库连接失败: " + e.getMessage(), e);
            }
            // ScriptException 属 DataAccessException 体系，自然向上传播由服务层映射 503
        }
    }
}
