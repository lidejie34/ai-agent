package com.dj.ai.agentchat.rag.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 知识库表懒建表器（迭代6，范式复刻 {@code ToolSchemaInitializer}）。
 *
 * <p>持 PG 第二数据源（{@code @Qualifier("ragDataSource")}），首次知识库路径调用
 * {@link #ensureSchema()}：经 DataSource 取连接 + spring-jdbc {@link ScriptUtils}
 * 执行 classpath {@code db/rag-schema.sql}（CREATE EXTENSION vector / 两表 /
 * HNSW 索引，全幂等）。{@link AtomicBoolean} 成功后短路；失败原样抛出且<b>不置位</b>
 * ——PG 恢复后下次调用重试。
 */
@Slf4j
public class RagSchemaInitializer {

    static final String SCHEMA_LOCATION = "db/rag-schema.sql";

    private final DataSource dataSource;
    private final AtomicBoolean created = new AtomicBoolean(false);

    public RagSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 确保 pgvector 扩展与 RAG 两表存在；首次执行建表脚本，成功后短路。
     * 失败抛 DataAccessException 体系异常（连接失败为
     * {@link CannotGetJdbcConnectionException}，脚本失败为 ScriptException）。
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
                log.info("RAG 表初始化完成（rag_document / rag_chunk + vector/HNSW）");
            } catch (CannotGetJdbcConnectionException e) {
                // 连接获取失败（Hikari 懒连接，PG 不可达时首借连接抛此异常）：直接传播
                throw e;
            } catch (SQLException e) {
                throw new CannotGetJdbcConnectionException("获取 RAG 数据库连接失败: " + e.getMessage(), e);
            }
            // ScriptException 属 DataAccessException 体系，自然向上传播由调用方 best-effort 处理
        }
    }
}
