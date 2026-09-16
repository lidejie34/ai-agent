package com.dj.ai.agentchat.memory.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionScopeMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

/**
 * 迭代8 真实库测试基础设施（本机 docker MySQL 13306，库 dj_agent，root/root）：
 * 程序化装配 MyBatis-Plus SqlSessionFactory（不启 Spring 上下文），
 * 暴露真实 {@link ChatMessageMapper}/{@link ChatSessionMapper}。
 *
 * <p>隔离约定：测试会话 ID 统一 {@code evidence-test-} 前缀（36 字符以内），
 * 测试结束按前缀清理 chat_message / chat_session，不污染真实会话数据。
 * DB 不可达时整类 assume 跳过（本地/CI 无 docker 环境不红）。
 */
public final class RealDbTestSupport {

    public static final String JDBC_URL =
            "jdbc:mysql://127.0.0.1:13306/dj_agent?useUnicode=true&characterEncoding=utf8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";
    public static final String SESSION_PREFIX = "evidence-test-";

    private RealDbTestSupport() {
    }

    /** DB 可达性检查（TCP 13306 1s 超时）；不可达时调用方 assumeTrue 跳过。 */
    public static boolean dbReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 13306), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void assumeDbReachable() {
        Assumptions.assumeTrue(dbReachable(), "本机 docker MySQL 13306 不可达，跳过真实库测试");
    }

    /** 生成带隔离前缀的会话 ID（VARCHAR(36) 内）。 */
    public static String newSessionId() {
        return SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    /**
     * 生成标准 UUID 会话 ID（供 SessionService 等要求 36 位 UUID 格式的路径）；
     * 调用方须 {@link RealDbHarness#track(String)} 登记，cleanup 按精确 ID 删除。
     */
    public static String newUuidSessionId() {
        return UUID.randomUUID().toString();
    }

    /** 真实库装配：SqlSessionFactory（自动提交）+ 两个 Mapper + 建表器。 */
    public static RealDbHarness harness() {
        org.apache.ibatis.datasource.pooled.PooledDataSource dataSource =
                new org.apache.ibatis.datasource.pooled.PooledDataSource(
                        "com.mysql.cj.jdbc.Driver", JDBC_URL, "root", "root");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(
                new Environment("realdb", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ChatSessionMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(ChatSessionScopeMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSession sqlSession = factory.openSession(true);
        return new RealDbHarness(dataSource, sqlSession,
                sqlSession.getMapper(ChatSessionMapper.class),
                sqlSession.getMapper(ChatMessageMapper.class),
                sqlSession.getMapper(ChatSessionScopeMapper.class));
    }

    /** 持有测试期资源；close 清理全部 evidence-test-% 数据并关闭会话/连接池。 */
    public static final class RealDbHarness implements AutoCloseable {

        private final DataSource dataSource;
        private final SqlSession sqlSession;
        public final ChatSessionMapper sessionMapper;
        public final ChatMessageMapper messageMapper;
        public final ChatSessionScopeMapper scopeMapper;
        public final ChatMemorySchemaInitializer schemaInitializer;
        public final MybatisChatMemory chatMemory;
        public final MybatisSessionManager sessionManager;
        private final java.util.List<String> trackedSessionIds =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private RealDbHarness(DataSource dataSource, SqlSession sqlSession,
                              ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                              ChatSessionScopeMapper scopeMapper) {
            this.dataSource = dataSource;
            this.sqlSession = sqlSession;
            this.sessionMapper = sessionMapper;
            this.messageMapper = messageMapper;
            this.scopeMapper = scopeMapper;
            this.schemaInitializer = new ChatMemorySchemaInitializer(dataSource);
            this.chatMemory = new MybatisChatMemory(sessionMapper, messageMapper, schemaInitializer);
            this.sessionManager = new MybatisSessionManager(
                    sessionMapper, messageMapper, schemaInitializer, scopeMapper);
        }

        /** 底层数据源（回归 SQL 直跑等 JDBC 场景）。 */
        public DataSource dataSource() {
            return dataSource;
        }

        /** 登记标准 UUID 会话（无前缀），cleanup 时按精确 ID 删除。 */
        public void track(String sessionId) {
            trackedSessionIds.add(sessionId);
        }

        /** 按隔离前缀 + 登记的精确 ID 清理（兜底测试中断残留的脏数据）。 */
        public void cleanup() {
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM chat_message WHERE session_id LIKE '"
                        + SESSION_PREFIX + "%'");
                stmt.executeUpdate("DELETE FROM chat_session_scope WHERE session_id LIKE '"
                        + SESSION_PREFIX + "%'");
                stmt.executeUpdate("DELETE FROM chat_session WHERE session_id LIKE '"
                        + SESSION_PREFIX + "%'");
            } catch (Exception e) {
                // 清理失败不影响测试结果（下次 @BeforeEach 还会再清）
            }
            for (String sid : trackedSessionIds) {
                try {
                    messageMapper.deleteBySessionId(sid);
                    scopeMapper.deleteBySessionId(sid);
                    sessionMapper.deleteById(sid);
                } catch (Exception e) {
                    // ignore
                }
            }
            trackedSessionIds.clear();
        }

        @Override
        public void close() {
            cleanup();
            try {
                sqlSession.close();
            } catch (Exception e) {
                // ignore
            }
            if (dataSource instanceof org.apache.ibatis.datasource.pooled.PooledDataSource pooled) {
                pooled.forceCloseAll();
            }
        }
    }
}
