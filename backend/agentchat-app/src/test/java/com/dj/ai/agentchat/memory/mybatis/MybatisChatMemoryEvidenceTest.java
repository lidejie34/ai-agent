package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.ToolEvidenceMessage;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3（迭代8，真实库 MySQL 13306）：证据落库与回放——
 * <ul>
 *   <li>add 落 [user, tool_evidence, assistant] 三行 role 顺序与 id 递增；</li>
 *   <li>get 回放 tool_evidence → AssistantMessage 且带「【此前工具查证记录】\n」前缀；</li>
 *   <li>22 条对话 + 3 条证据 → get(sid,20) = 最近 20 条对话 + 窗口内全部证据（证据不占窗口）；</li>
 *   <li>无证据会话 selectRecent 与旧 SQL 逐行一致（回归）。</li>
 * </ul>
 * 会话 ID 统一 evidence-test- 前缀，每个用例前后清理，不污染真实会话数据。
 */
class MybatisChatMemoryEvidenceTest {

    private RealDbTestSupport.RealDbHarness db;

    @BeforeAll
    static void assumeDb() {
        RealDbTestSupport.assumeDbReachable();
    }

    @BeforeEach
    void setUp() {
        db = RealDbTestSupport.harness();
        db.cleanup(); // 兜底清理历史残留
        db.schemaInitializer.ensureSchema();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void add_persistsUserEvidenceAssistant_rowsInOrderWithIncreasingIds() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);

        db.chatMemory.add(sid, List.of(
                new UserMessage("查一下 uk=123 的日志"),
                new ToolEvidenceMessage("skyeye_query_log | SUCCESS | 12ms | 入参: {} | 结果: ERROR boom"),
                new AssistantMessage("查到一条错误日志")));

        List<ChatMessagePO> rows = db.messageMapper.selectAllAscending(sid);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ChatMessagePO::getRole)
                .containsExactly("user", "tool_evidence", "assistant");
        assertThat(rows.get(0).getId()).isLessThan(rows.get(1).getId());
        assertThat(rows.get(1).getId()).isLessThan(rows.get(2).getId());
        assertThat(rows.get(1).getContent()).contains("skyeye_query_log | SUCCESS");
    }

    @Test
    void get_replaysEvidenceAsAssistantMessage_withReplayPrefix() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        db.chatMemory.add(sid, List.of(
                new UserMessage("问题"),
                new ToolEvidenceMessage("demo_tool | FAILED | 3ms | 入参: {} | 结果: boom"),
                new AssistantMessage("回答")));

        List<Message> messages = db.chatMemory.get(sid, 20);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
        // 证据行回放为带前缀的 AssistantMessage（读侧无开关门控）
        assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(1).getText()).startsWith(
                MybatisChatMemory.EVIDENCE_REPLAY_PREFIX);
        assertThat(messages.get(1).getText()).contains("demo_tool | FAILED");
        assertThat(messages.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(2).getText()).isEqualTo("回答");
    }

    @Test
    void get_windowCountsDialogueOnly_evidenceInsideWindowIncluded() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        // 11 轮对话（22 条）+ 3 条证据交错：e1 在窗口外，e2/e3 在窗口内
        List<Message> batch = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            batch.add(new UserMessage("u" + i));
            batch.add(new AssistantMessage("a" + i));
            if (i == 1) {
                batch.add(new ToolEvidenceMessage("tool_old | SUCCESS | 1ms | 入参: {} | 结果: old"));
            }
            if (i == 8) {
                batch.add(new ToolEvidenceMessage("tool_mid | SUCCESS | 2ms | 入参: {} | 结果: mid"));
            }
        }
        batch.add(new ToolEvidenceMessage("tool_new | SUCCESS | 3ms | 入参: {} | 结果: new"));
        db.chatMemory.add(sid, batch);

        List<Message> window = db.chatMemory.get(sid, 20);

        // 窗口恰好 20 条对话（u2..a11）+ 窗口内 2 条证据（tool_mid / tool_new），证据不挤占窗口
        assertThat(window).hasSize(22);
        long dialogue = window.stream()
                .filter(m -> !m.getText().startsWith(MybatisChatMemory.EVIDENCE_REPLAY_PREFIX))
                .count();
        assertThat(dialogue).isEqualTo(20);
        // 窗口内证据随对话行一起取回且带前缀；窗口外旧证据自然淘汰
        List<String> texts = window.stream().map(Message::getText).toList();
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("tool_mid | SUCCESS"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("tool_new | SUCCESS"));
        assertThat(texts).noneSatisfy(t -> assertThat(t).contains("tool_old"));
        // 时序：首条为 u2（窗口下界），末条为最新证据
        assertThat(texts.get(0)).isEqualTo("u2");
        assertThat(texts.get(texts.size() - 1)).contains("tool_new | SUCCESS");
    }

    @Test
    void selectRecent_noEvidenceSession_rowIdenticalToLegacySql() throws Exception {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        List<Message> batch = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            batch.add(i % 2 == 1 ? new UserMessage("msg-" + i) : new AssistantMessage("msg-" + i));
        }
        db.chatMemory.add(sid, batch);

        List<ChatMessagePO> actual = db.messageMapper.selectRecent(sid, 20);
        List<ChatMessagePO> legacy = runLegacySelectRecent(sid, 20);

        // 与旧 SQL（派生表倒序 LIMIT + 外层正序）逐行一致（回归基线）
        assertThat(actual).hasSize(20);
        assertThat(actual).usingRecursiveComparison().isEqualTo(legacy);
        assertThat(actual.get(0).getContent()).isEqualTo("msg-6");
        assertThat(actual.get(19).getContent()).isEqualTo("msg-25");
    }

    /** 旧版 selectRecent SQL 直跑（JDBC），作为回归对比基线。 */
    private List<ChatMessagePO> runLegacySelectRecent(String sid, int n) throws Exception {
        String legacySql = "SELECT id, session_id, role, content, created_at FROM "
                + "(SELECT * FROM chat_message WHERE session_id = ? ORDER BY id DESC LIMIT ?) t "
                + "ORDER BY t.id ASC";
        List<ChatMessagePO> rows = new ArrayList<>();
        try (Connection conn = db.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(legacySql)) {
            ps.setString(1, sid);
            ps.setInt(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessagePO po = new ChatMessagePO();
                    po.setId(rs.getLong("id"));
                    po.setSessionId(rs.getString("session_id"));
                    po.setRole(rs.getString("role"));
                    po.setContent(rs.getString("content"));
                    po.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    rows.add(po);
                }
            }
        }
        return rows;
    }
}
