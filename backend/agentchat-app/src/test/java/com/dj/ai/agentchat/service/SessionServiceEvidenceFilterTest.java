package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.memory.ToolEvidenceMessage;
import com.dj.ai.agentchat.memory.mybatis.RealDbTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4（迭代8，真实库 MySQL 13306）：证据行用户出口不可见——
 * <ul>
 *   <li>listMessages 无 tool_evidence 行，其余行逐字段一致；</li>
 *   <li>会话列表预览（selectLatestPerSession）取最近一条对话而非证据（证据为最新行也不污染）；</li>
 *   <li>无证据会话 listMessages 与过滤前逐字段一致（回归）。</li>
 * </ul>
 * 会话 ID 为标准 UUID（SessionService 校验 36 位 UUID），经 harness.track 登记，
 * 每个用例前后按精确 ID 清理，不污染真实会话数据。
 */
class SessionServiceEvidenceFilterTest {

    private RealDbTestSupport.RealDbHarness db;
    private SessionService sessionService;

    @BeforeAll
    static void assumeDb() {
        RealDbTestSupport.assumeDbReachable();
    }

    @BeforeEach
    void setUp() {
        db = RealDbTestSupport.harness();
        db.cleanup();
        db.schemaInitializer.ensureSchema();
        sessionService = new SessionService(
                new ChatService.FixedObjectProvider<>(db.sessionManager), true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** SessionService 要求 36 位 UUID 会话 ID：用标准 UUID 并登记清理（不污染真实数据）。 */
    private String newTrackedSession() {
        String sid = RealDbTestSupport.newUuidSessionId();
        db.track(sid);
        return sid;
    }

    @Test
    void listMessages_filtersOutToolEvidence_keepsDialogueVerbatim() {
        String sid = newTrackedSession();
        db.chatMemory.createSessionIfAbsent(sid);
        db.chatMemory.add(sid, List.of(
                new UserMessage("查 uk=123 日志"),
                new ToolEvidenceMessage("skyeye_query_log | SUCCESS | 9ms | 入参: {} | 结果: boom"),
                new AssistantMessage("发现一条 ERROR")));

        List<SessionMessageView> views = sessionService.listMessages(sid);

        assertThat(views).hasSize(2);
        assertThat(views).extracting(SessionMessageView::role)
                .containsExactly("user", "assistant");
        assertThat(views.get(0).content()).isEqualTo("查 uk=123 日志");
        assertThat(views.get(1).content()).isEqualTo("发现一条 ERROR");
        assertThat(views).noneSatisfy(v -> assertThat(v.role()).isEqualTo("tool_evidence"));
        assertThat(views.get(0).createdAt()).isNotNull();
    }

    @Test
    void sessionPreview_latestMessageIsDialogue_notEvidenceEvenWhenEvidenceIsNewest() {
        String sid = newTrackedSession();
        db.chatMemory.createSessionIfAbsent(sid);
        // 证据行人为置于最末（id 最大）——预览仍须取最近一条对话
        db.chatMemory.add(sid, List.of(
                new UserMessage("问题一"),
                new AssistantMessage("回答一")));
        db.chatMemory.add(sid, List.of(
                new ToolEvidenceMessage("demo_tool | FAILED | 5ms | 入参: {} | 结果: 错误摘要")));

        List<SessionSummary> summaries = sessionService.listSessions();

        SessionSummary summary = summaries.stream()
                .filter(s -> s.sessionId().equals(sid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("会话未出现在列表中"));
        assertThat(summary.previewRole()).isEqualTo("assistant");
        assertThat(summary.previewText()).contains("回答一");
        assertThat(summary.previewText()).doesNotContain("demo_tool");
    }

    @Test
    void listMessages_noEvidenceSession_verbatimRegression() {
        String sid = newTrackedSession();
        db.chatMemory.createSessionIfAbsent(sid);
        db.chatMemory.add(sid, List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好，有什么可以帮你？"),
                new UserMessage("我叫小明"),
                new AssistantMessage("你好小明")));

        List<SessionMessageView> views = sessionService.listMessages(sid);

        assertThat(views).extracting(SessionMessageView::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(views).extracting(SessionMessageView::content)
                .containsExactly("你好", "你好，有什么可以帮你？", "我叫小明", "你好小明");
    }
}
