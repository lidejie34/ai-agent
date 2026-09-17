package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.ToolEvidenceMessage;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13-M（迭代13，真实库 MySQL 13306）：清空上下文标记点回放语义 + 消息级删除 SQL——
 * <ul>
 *   <li>get 只回放最近一次 context_reset 标记点之后的消息；标记行本身不回放；</li>
 *   <li>多次清空以最新标记生效；无标记会话与旧行为逐行一致（回归）；</li>
 *   <li>deleteRange/deleteFrom 覆盖 user+evidence+assistant 且跳过 context_reset；</li>
 *   <li>selectNextUserId 边界；findMessage 跨会话防越权。</li>
 * </ul>
 * 会话 ID 统一隔离前缀，每个用例前后清理，不污染真实会话数据。
 */
class MybatisChatMemoryContextResetTest {

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

    /** 落一轮完整对话（user + evidence + assistant），返回三行（id 升序）。 */
    private List<ChatMessagePO> addTurn(String sid, String tag) {
        db.chatMemory.add(sid, List.of(
                new UserMessage("问题-" + tag),
                new ToolEvidenceMessage("demo_tool | SUCCESS | 1ms | 入参: {} | 结果: r-" + tag),
                new AssistantMessage("回答-" + tag)));
        List<ChatMessagePO> rows = db.messageMapper.selectAllAscending(sid);
        return rows.subList(rows.size() - 3, rows.size());
    }

    @Test
    void get_replaysOnlyMessagesAfterLatestResetMarker() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        addTurn(sid, "t1");
        addTurn(sid, "t2");
        db.sessionManager.insertContextReset(sid);
        addTurn(sid, "t3");

        List<Message> replayed = db.chatMemory.get(sid, 20);

        // 标记点之后的一轮 = user + evidence（回放为 AssistantMessage 带前缀）+ assistant
        assertThat(replayed).hasSize(3);
        assertThat(replayed.get(0).getText()).isEqualTo("问题-t3");
        assertThat(replayed.get(1).getText()).contains("r-t3");
        assertThat(replayed.get(2).getText()).isEqualTo("回答-t3");
        // 标记行与标记点之前的内容一概不回放
        assertThat(replayed).noneMatch(m -> m.getText().contains("t1") || m.getText().contains("t2"));
    }

    @Test
    void get_multipleResets_latestMarkerWins() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        addTurn(sid, "a");
        db.sessionManager.insertContextReset(sid);
        addTurn(sid, "b");
        db.sessionManager.insertContextReset(sid);
        addTurn(sid, "c");

        List<Message> replayed = db.chatMemory.get(sid, 20);

        assertThat(replayed).hasSize(3);
        assertThat(replayed.get(0).getText()).isEqualTo("问题-c");
        assertThat(replayed).noneMatch(m -> m.getText().contains("-a") || m.getText().contains("-b"));
    }

    @Test
    void get_withoutMarker_regressionUnchanged() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        addTurn(sid, "x");
        addTurn(sid, "y");

        List<Message> replayed = db.chatMemory.get(sid, 20);

        assertThat(replayed).hasSize(6);
        assertThat(replayed.get(0).getText()).isEqualTo("问题-x");
        assertThat(replayed.get(5).getText()).isEqualTo("回答-y");
    }

    @Test
    void deleteRange_coversTurnAndSkipsContextReset() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        List<ChatMessagePO> t1 = addTurn(sid, "t1");
        ChatMessagePO marker = db.sessionManager.insertContextReset(sid);
        List<ChatMessagePO> t2 = addTurn(sid, "t2");

        // 删第一轮 [u1, u2)：区间内 user+evidence+assistant 全删，标记行跳过保留
        int deleted = db.messageMapper.deleteRange(sid, t1.get(0).getId(), t2.get(0).getId());

        assertThat(deleted).isEqualTo(3);
        List<ChatMessagePO> remaining = db.messageMapper.selectAllAscending(sid);
        assertThat(remaining).extracting(ChatMessagePO::getRole)
                .containsExactly("context_reset", "user", "tool_evidence", "assistant");
        assertThat(remaining.get(0).getId()).isEqualTo(marker.getId());
    }

    @Test
    void deleteFrom_truncatesToEndAndSkipsContextReset() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        addTurn(sid, "t1");
        List<ChatMessagePO> t2 = addTurn(sid, "t2");
        ChatMessagePO marker = db.sessionManager.insertContextReset(sid);
        List<ChatMessagePO> t3 = addTurn(sid, "t3");

        // 从第二轮 user 截断：t2/t3 全删，尾部标记行保留（记忆边界不随截断消失）
        int deleted = db.messageMapper.deleteFrom(sid, t2.get(0).getId());

        assertThat(deleted).isEqualTo(6);
        List<ChatMessagePO> remaining = db.messageMapper.selectAllAscending(sid);
        assertThat(remaining).extracting(ChatMessagePO::getRole)
                .containsExactly("user", "tool_evidence", "assistant", "context_reset");
        assertThat(remaining.get(3).getId()).isEqualTo(marker.getId());
        assertThat(t3.get(0).getId()).isGreaterThan(marker.getId()); // 标记确在被删区间之后
    }

    @Test
    void selectNextUserId_boundaries() {
        String sid = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        List<ChatMessagePO> t1 = addTurn(sid, "t1");
        List<ChatMessagePO> t2 = addTurn(sid, "t2");

        assertThat(db.messageMapper.selectNextUserId(sid, t1.get(0).getId()))
                .isEqualTo(t2.get(0).getId());
        // 末轮无后继 user → null
        assertThat(db.messageMapper.selectNextUserId(sid, t2.get(0).getId())).isNull();
    }

    @Test
    void findMessage_crossSession_returnsNull() {
        String sid = RealDbTestSupport.newSessionId();
        String other = RealDbTestSupport.newSessionId();
        db.chatMemory.createSessionIfAbsent(sid);
        db.chatMemory.createSessionIfAbsent(other);
        List<ChatMessagePO> t1 = addTurn(sid, "t1");

        assertThat(db.sessionManager.findMessage(sid, t1.get(0).getId())).isNotNull();
        // 跨会话 id → null（防越权删除他会话消息）
        assertThat(db.sessionManager.findMessage(other, t1.get(0).getId())).isNull();
        assertThat(db.sessionManager.findMessage(sid, 999999999L)).isNull();
    }
}
