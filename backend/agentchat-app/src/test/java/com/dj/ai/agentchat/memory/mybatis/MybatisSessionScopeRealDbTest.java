package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代12 真实库（本机 docker MySQL 13306）：chat_session_scope 的 upsert 幂等、
 * 三态（NULL vs '[]' vs 非空 JSON）逐字节保持、级联删除随会话清理。
 * DB 不可达时整类 assume 跳过（同 RealDbTestSupport 约定）。
 */
class MybatisSessionScopeRealDbTest {

    private RealDbTestSupport.RealDbHarness harness;

    @BeforeEach
    void setUp() {
        RealDbTestSupport.assumeDbReachable();
        harness = RealDbTestSupport.harness();
        harness.schemaInitializer.ensureSchema();
        harness.cleanup();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /** 建一行真实会话并登记清理（scope 行挂 session_id 主键，会话行须存在语义才完整）。 */
    private String newSession() {
        String sid = RealDbTestSupport.newUuidSessionId();
        harness.track(sid);
        ChatSessionPO session = new ChatSessionPO();
        session.setSessionId(sid);
        harness.sessionMapper.insert(session);
        return sid;
    }

    private static ChatSessionScopePO scopePo(String sid, String kbProjects, String kbTags,
                                              String toolNames, String mcpServers) {
        ChatSessionScopePO po = new ChatSessionScopePO();
        po.setSessionId(sid);
        po.setKbProjects(kbProjects);
        po.setKbTags(kbTags);
        po.setToolNames(toolNames);
        po.setMcpServers(mcpServers);
        return po;
    }

    @Test
    void upsertThenFind_roundTripsThreeState_bytePreserved() {
        String sid = newSession();

        harness.sessionManager.upsertScope(scopePo(sid, "[\"订单域\"]", null, "[]", null));

        ChatSessionScopePO found = harness.sessionManager.findScope(sid);
        assertThat(found).isNotNull();
        // 三态逐字节：非空 JSON / NULL（默认全部）/ '[]'（显式全不选）互不串扰
        assertThat(found.getKbProjects()).isEqualTo("[\"订单域\"]");
        assertThat(found.getKbTags()).isNull();
        assertThat(found.getToolNames()).isEqualTo("[]");
        assertThat(found.getMcpServers()).isNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void upsert_twice_overwritesAllColumns_includingNulls() {
        String sid = newSession();
        harness.sessionManager.upsertScope(scopePo(sid, "[\"订单域\"]", "[\"售后\"]", "[\"analyze_log\"]", "[\"easy-mysql\"]"));

        // 第二次全量覆盖：NULL 列也被显式写回（三态语义要求「恢复默认」可表达）
        harness.sessionManager.upsertScope(scopePo(sid, null, null, "[]", "[]"));

        ChatSessionScopePO found = harness.sessionManager.findScope(sid);
        assertThat(found.getKbProjects()).isNull();
        assertThat(found.getKbTags()).isNull();
        assertThat(found.getToolNames()).isEqualTo("[]");
        assertThat(found.getMcpServers()).isEqualTo("[]");
    }

    @Test
    void deleteCascade_removesScopeRowWithSession() {
        String sid = newSession();
        harness.sessionManager.upsertScope(scopePo(sid, null, null, null, null));
        assertThat(harness.sessionManager.findScope(sid)).isNotNull();

        harness.sessionManager.deleteCascade(sid);

        assertThat(harness.sessionManager.findScope(sid)).isNull();
        assertThat(harness.sessionManager.findSession(sid)).isNull();
    }

    @Test
    void findScope_noRow_returnsNull() {
        String sid = newSession();
        assertThat(harness.sessionManager.findScope(sid)).isNull();
    }
}
