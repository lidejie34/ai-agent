package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.session.BatchSessionDeleteRequest;
import com.dj.ai.agentchat.dto.session.BatchSessionDeleteResult;
import com.dj.ai.agentchat.dto.session.ContextResetView;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T13-S（迭代13，纯 Mockito 离线）：{@link SessionService} 消息级操作——
 * 删单轮（区间/末轮/非 user 锚点 400/消息 404/跨会话 404）、截断（user/assistant 锚点）、
 * 清空上下文（标记视图返回）、批量删会话（空/非法 400、存在+不存在混合）、
 * listMessages 排除式过滤（context_reset 放行、tool_evidence 拦截、带库 id）。
 */
class SessionServiceMessageOpsTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SID2 = "223e4567-e89b-12d3-a456-426614174001";

    private SessionManager manager;
    private SessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(SessionManager.class);
        ObjectProvider<SessionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        service = new SessionService(provider, true);
        when(manager.findSession(SID)).thenReturn(sessionPo(SID));
    }

    private static ChatSessionPO sessionPo(String sid) {
        ChatSessionPO po = new ChatSessionPO();
        po.setSessionId(sid);
        return po;
    }

    private static ChatMessagePO msg(long id, String role) {
        ChatMessagePO po = new ChatMessagePO();
        po.setId(id);
        po.setSessionId(SessionServiceMessageOpsTest.SID);
        po.setRole(role);
        po.setContent("c" + id);
        po.setCreatedAt(LocalDateTime.of(2026, 9, 17, 10, 0, 0));
        return po;
    }

    // ---------- 删单轮 ----------

    @Test
    void deleteTurn_userAnchorWithNextUser_deletesRange() {
        when(manager.findMessage(SID, 10L)).thenReturn(msg(10L, "user"));
        when(manager.findNextUserId(SID, 10L)).thenReturn(30L);

        service.deleteTurn(SID, 10L);

        verify(manager).deleteMessageRange(SID, 10L, 30L);
        verify(manager, never()).deleteMessagesFrom(anyString(), anyLong());
    }

    @Test
    void deleteTurn_userAnchorLastTurn_deletesToEnd() {
        when(manager.findMessage(SID, 10L)).thenReturn(msg(10L, "user"));
        when(manager.findNextUserId(SID, 10L)).thenReturn(null);

        service.deleteTurn(SID, 10L);

        verify(manager).deleteMessagesFrom(SID, 10L);
        verify(manager, never()).deleteMessageRange(anyString(), anyLong(), anyLong());
    }

    @Test
    void deleteTurn_assistantAnchor_400() {
        when(manager.findMessage(SID, 12L)).thenReturn(msg(12L, "assistant"));

        assertThatThrownBy(() -> service.deleteTurn(SID, 12L))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("用户消息");
        verify(manager, never()).deleteMessageRange(anyString(), anyLong(), anyLong());
        verify(manager, never()).deleteMessagesFrom(anyString(), anyLong());
    }

    @Test
    void deleteTurn_messageMissingOrCrossSession_404() {
        // findMessage 内部已做会话归属校验，跨会话返回 null → 404
        when(manager.findMessage(SID, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteTurn(SID, 99L))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessageContaining("消息不存在");
    }

    // ---------- 截断 ----------

    @Test
    void truncate_userAnchor_deletesFromAnchor() {
        when(manager.findMessage(SID, 10L)).thenReturn(msg(10L, "user"));

        service.truncateMessages(SID, 10L);

        verify(manager).deleteMessagesFrom(SID, 10L);
    }

    @Test
    void truncate_assistantAnchor_allowed() {
        when(manager.findMessage(SID, 12L)).thenReturn(msg(12L, "assistant"));

        service.truncateMessages(SID, 12L);

        verify(manager).deleteMessagesFrom(SID, 12L);
    }

    @Test
    void truncate_messageMissing_404() {
        when(manager.findMessage(SID, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.truncateMessages(SID, 99L))
                .isInstanceOf(SessionNotFoundException.class);
        verify(manager, never()).deleteMessagesFrom(anyString(), anyLong());
    }

    // ---------- 清空上下文 ----------

    @Test
    void clearContext_insertsMarkerAndReturnsView() {
        ChatMessagePO marker = msg(50L, "context_reset");
        marker.setContent("");
        when(manager.insertContextReset(SID)).thenReturn(marker);

        ContextResetView view = service.clearContext(SID);

        assertThat(view.id()).isEqualTo(50L);
        assertThat(view.createdAt()).isEqualTo(marker.getCreatedAt());
    }

    @Test
    void clearContext_sessionMissing_404() {
        when(manager.findSession("333e4567-e89b-12d3-a456-426614174002")).thenReturn(null);

        assertThatThrownBy(() -> service.clearContext("333e4567-e89b-12d3-a456-426614174002"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ---------- 批量删会话 ----------

    @Test
    void batchDelete_emptyIds_400() {
        assertThatThrownBy(() -> service.batchDeleteSessions(new BatchSessionDeleteRequest(List.of())))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("ids 不能为空");
        assertThatThrownBy(() -> service.batchDeleteSessions(null))
                .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void batchDelete_malformedUuid_400AndNothingDeleted() {
        assertThatThrownBy(() -> service.batchDeleteSessions(
                new BatchSessionDeleteRequest(List.of(SID, "not-a-uuid"))))
                .isInstanceOf(InvalidChatRequestException.class);
        verify(manager, never()).deleteCascade(anyString());
    }

    @Test
    void batchDelete_mixedExistingAndMissing_perIdResult() {
        when(manager.findSession(SID2)).thenReturn(null);

        BatchSessionDeleteResult result = service.batchDeleteSessions(
                new BatchSessionDeleteRequest(List.of(SID, SID2)));

        assertThat(result.deleted()).containsExactly(SID);
        assertThat(result.notFound()).containsExactly(SID2);
        verify(manager).deleteCascade(SID);
        verify(manager, never()).deleteCascade(SID2);
    }

    // ---------- listMessages 排除式过滤 ----------

    @Test
    void listMessages_contextResetPassesThrough_evidenceHidden_withIds() {
        when(manager.listMessagesAscending(SID)).thenReturn(List.of(
                msg(1L, "user"),
                msg(2L, "context_reset"),
                msg(3L, "tool_evidence"),
                msg(4L, "assistant")));

        List<SessionMessageView> views = service.listMessages(SID);

        assertThat(views).extracting(SessionMessageView::role)
                .containsExactly("user", "context_reset", "assistant");
        assertThat(views).extracting(SessionMessageView::id)
                .containsExactly(1L, 2L, 4L);
    }
}
