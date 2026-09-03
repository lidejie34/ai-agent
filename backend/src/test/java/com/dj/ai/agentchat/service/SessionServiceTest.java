package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.session.SessionDeleteResult;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.util.TextTitleUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataRetrievalFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3：{@link SessionService} 单测——纯 Mockito（离线，不引 H2/Testcontainers）。
 * 覆盖：列表倒序 + 预览组装 + 空会话 null + 0 会话跳过 Q2（无 N+1）；历史升序；
 * 删除/重命名 404/400/trim/超长；记忆开关关闭/manager 缺席 → 400；DataAccessException → 503。
 */
class SessionServiceTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER = "223e4567-e89b-12d3-a456-426614174001";

    private SessionManager manager;
    private ObjectProvider<SessionManager> provider;
    private SessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(SessionManager.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        service = new SessionService(provider, true);
    }

    private SessionService disabledService() {
        return new SessionService(provider, false);
    }

    private SessionService absentManagerService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionManager> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        return new SessionService(empty, true);
    }

    // ---------- 列表 ----------

    @Test
    void listSessions_descOrder_withPreviewAssembled_andEmptySessionNullPreview() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 3, 9, 0);
        ChatSessionPO a = session(SID, "标题A", t1, t2);
        ChatSessionPO b = session(OTHER, null, t1, t1); // 空会话：title null、无消息
        when(manager.listSessions()).thenReturn(List.of(a, b));
        String longContent = "这是一条很长的助手回复内容需要被截断用于预览展示一二三五六七八九十";
        when(manager.latestMessages(anyCollection()))
                .thenReturn(List.of(message(SID, "assistant", longContent)));

        List<SessionSummary> result = service.listSessions();

        assertThat(result).hasSize(2);
        // 倒序透传（manager 已按 updated_at DESC 返回）
        assertThat(result.get(0).sessionId()).isEqualTo(SID);
        assertThat(result.get(0).title()).isEqualTo("标题A");
        assertThat(result.get(0).previewRole()).isEqualTo("assistant");
        assertThat(result.get(0).previewText()).isEqualTo(TextTitleUtils.buildPreview(longContent));
        assertThat(result.get(0).updatedAt()).isEqualTo(t2);
        // 空会话：preview 字段为 null（fastjson2 省略键）
        assertThat(result.get(1).sessionId()).isEqualTo(OTHER);
        assertThat(result.get(1).title()).isNull();
        assertThat(result.get(1).previewRole()).isNull();
        assertThat(result.get(1).previewText()).isNull();
    }

    @Test
    void listSessions_empty_returnsEmptyAndSkipsPreviewQuery() {
        when(manager.listSessions()).thenReturn(List.of());

        assertThat(service.listSessions()).isEmpty();
        verify(manager, never()).latestMessages(anyCollection());
    }

    @Test
    void listSessions_hundredSessions_constantCalls_noNplus1() {
        List<ChatSessionPO> sessions = new ArrayList<>();
        List<ChatMessagePO> latest = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String sid = "00000000-0000-0000-0000-" + String.format("%012d", i);
            sessions.add(session(sid, "标题" + i, LocalDateTime.now(), LocalDateTime.now()));
            latest.add(message(sid, "user", "消息" + i));
        }
        when(manager.listSessions()).thenReturn(sessions);
        when(manager.latestMessages(anyCollection())).thenReturn(latest);

        List<SessionSummary> result = service.listSessions();

        assertThat(result).hasSize(100);
        // 常数次查询：listSessions 1 次 + latestMessages 1 次（不随会话数线性增长）
        verify(manager, times(1)).listSessions();
        verify(manager, times(1)).latestMessages(anyCollection());
    }

    // ---------- 历史消息 ----------

    @Test
    void listMessages_returnsAscendingViews_fullText() {
        when(manager.findSession(SID)).thenReturn(session(SID, "标题", null, null));
        when(manager.listMessagesAscending(SID)).thenReturn(List.of(
                message(SID, "user", "你好"),
                message(SID, "assistant", "你好呀")));

        List<SessionMessageView> views = service.listMessages(SID);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).role()).isEqualTo("user");
        assertThat(views.get(0).content()).isEqualTo("你好");
        assertThat(views.get(1).role()).isEqualTo("assistant");
        assertThat(views.get(1).content()).isEqualTo("你好呀");
    }

    @Test
    void listMessages_illegalUuid_throws400_withoutTouchingManager() {
        assertThatThrownBy(() -> service.listMessages("not-a-uuid"))
                .isInstanceOf(InvalidChatRequestException.class);
        verify(manager, never()).findSession(anyString());
    }

    @Test
    void listMessages_notFound_throws404() {
        when(manager.findSession(SID)).thenReturn(null);
        assertThatThrownBy(() -> service.listMessages(SID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ---------- 删除 ----------

    @Test
    void deleteSession_success_returnsOk() {
        when(manager.findSession(SID)).thenReturn(session(SID, "标题", null, null));

        assertThat(service.deleteSession(SID)).isEqualTo(SessionDeleteResult.OK);
        verify(manager).deleteCascade(SID);
    }

    @Test
    void deleteSession_notFound_throws404_andDoesNotDelete() {
        when(manager.findSession(SID)).thenReturn(null);
        assertThatThrownBy(() -> service.deleteSession(SID))
                .isInstanceOf(SessionNotFoundException.class);
        verify(manager, never()).deleteCascade(anyString());
    }

    @Test
    void deleteSession_illegalUuid_throws400() {
        assertThatThrownBy(() -> service.deleteSession(SID + "x"))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    // ---------- 重命名 ----------

    @Test
    void renameSession_trimsAndStores_returnsSummaryWithoutPreview() {
        when(manager.findSession(SID)).thenReturn(session(SID, "旧标题", null, null));
        LocalDateTime newTime = LocalDateTime.of(2026, 9, 3, 12, 0);
        when(manager.rename(eq(SID), eq("新标题"))).thenReturn(session(SID, "新标题", null, newTime));

        SessionSummary result = service.renameSession(SID, "  新标题  ");

        verify(manager).rename(SID, "新标题");
        assertThat(result.title()).isEqualTo("新标题");
        assertThat(result.sessionId()).isEqualTo(SID);
        assertThat(result.updatedAt()).isEqualTo(newTime);
        // PATCH 响应不含预览（null 键省略）
        assertThat(result.previewRole()).isNull();
        assertThat(result.previewText()).isNull();
    }

    @Test
    void renameSession_blankTitle_throws400() {
        assertThatThrownBy(() -> service.renameSession(SID, "   "))
                .isInstanceOf(InvalidChatRequestException.class);
        verify(manager, never()).rename(anyString(), anyString());
    }

    @Test
    void renameSession_nullTitle_throws400() {
        assertThatThrownBy(() -> service.renameSession(SID, null))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void renameSession_over200CodePoints_throws400() {
        String tooLong = "字".repeat(201);
        assertThatThrownBy(() -> service.renameSession(SID, tooLong))
                .isInstanceOf(InvalidChatRequestException.class);
        verify(manager, never()).rename(anyString(), anyString());
    }

    @Test
    void renameSession_exactly200CodePoints_ok() {
        String ok200 = "字".repeat(200);
        when(manager.findSession(SID)).thenReturn(session(SID, "旧", null, null));
        when(manager.rename(eq(SID), eq(ok200))).thenReturn(session(SID, ok200, null, LocalDateTime.now()));

        assertThat(service.renameSession(SID, ok200).title()).hasSize(200);
    }

    @Test
    void renameSession_notFound_throws404() {
        when(manager.findSession(SID)).thenReturn(null);
        assertThatThrownBy(() -> service.renameSession(SID, "新标题"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ---------- 开关/装配 ----------

    @Test
    void memoryDisabled_listSessions_throws400() {
        assertThatThrownBy(() -> disabledService().listSessions())
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessage(ChatService.MEMORY_DISABLED_MESSAGE);
    }

    @Test
    void memoryDisabled_messages_throws400() {
        assertThatThrownBy(() -> disabledService().listMessages(SID))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void managerAbsent_throws400() {
        assertThatThrownBy(() -> absentManagerService().listSessions())
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessage(ChatService.MEMORY_DISABLED_MESSAGE);
    }

    // ---------- DB 异常 → 503 ----------

    @Test
    void listSessions_dataAccessException_wrappedTo503() {
        when(manager.listSessions()).thenThrow(new DataRetrievalFailureException("simulated db down"));
        assertThatThrownBy(() -> service.listSessions())
                .isInstanceOf(MemoryUnavailableException.class)
                .hasMessage(ChatService.MEMORY_UNAVAILABLE_MESSAGE);
    }

    @Test
    void listMessages_findSessionDataAccessException_wrappedTo503() {
        when(manager.findSession(SID)).thenThrow(new DataRetrievalFailureException("db down"));
        assertThatThrownBy(() -> service.listMessages(SID))
                .isInstanceOf(MemoryUnavailableException.class);
    }

    @Test
    void deleteSession_dataAccessException_wrappedTo503() {
        when(manager.findSession(SID)).thenReturn(session(SID, "t", null, null));
        org.mockito.Mockito.doThrow(new DataRetrievalFailureException("db down"))
                .when(manager).deleteCascade(SID);
        assertThatThrownBy(() -> service.deleteSession(SID))
                .isInstanceOf(MemoryUnavailableException.class);
    }

    @Test
    void renameSession_dataAccessException_wrappedTo503() {
        when(manager.findSession(SID)).thenReturn(session(SID, "t", null, null));
        when(manager.rename(anyString(), anyString()))
                .thenThrow(new DataRetrievalFailureException("db down"));
        assertThatThrownBy(() -> service.renameSession(SID, "新标题"))
                .isInstanceOf(MemoryUnavailableException.class);
    }

    // ---------- helpers ----------

    private static ChatSessionPO session(String sid, String title,
                                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        ChatSessionPO po = new ChatSessionPO();
        po.setSessionId(sid);
        po.setTitle(title);
        po.setCreatedAt(createdAt);
        po.setUpdatedAt(updatedAt);
        return po;
    }

    private static ChatMessagePO message(String sid, String role, String content) {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(sid);
        po.setRole(role);
        po.setContent(content);
        return po;
    }
}
