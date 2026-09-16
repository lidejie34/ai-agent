package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.session.SessionScopeUpdate;
import com.dj.ai.agentchat.dto.session.SessionScopeView;
import com.dj.ai.agentchat.exception.InvalidKbFilterException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionService 会话级范围配置（迭代12 FR-3/D2，mock SessionManager）：
 * 读侧——配置行缺席 → 全默认四 null；坏 JSON 防御为空数组；
 * 写侧——kb 两维白名单校验（非法 400）、toolNames/mcpServers 宽松规整、
 * 三态（null vs [] vs 非空）在落库 PO 上逐字节保持。
 */
class SessionServiceScopeTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";

    private SessionManager manager;
    private SessionService service;

    @BeforeEach
    void setUp() {
        manager = mock(SessionManager.class);
        service = new SessionService(new ChatService.FixedObjectProvider<>(manager), true);
        ChatSessionPO session = new ChatSessionPO();
        session.setSessionId(SID);
        when(manager.findSession(SID)).thenReturn(session);
    }

    // ---------- 读 ----------

    @Test
    void getScope_noRow_returnsAllDefaultNulls() {
        when(manager.findScope(SID)).thenReturn(null);

        SessionScopeView view = service.getScope(SID);

        assertThat(view.kbProjects()).isNull();
        assertThat(view.kbTags()).isNull();
        assertThat(view.toolNames()).isNull();
        assertThat(view.mcpServers()).isNull();
    }

    @Test
    void getScope_rowPresent_parsesJsonAndPreservesThreeState() {
        ChatSessionScopePO po = new ChatSessionScopePO();
        po.setSessionId(SID);
        po.setKbProjects("[\"订单域\",\"物流域\"]");
        po.setKbTags(null);
        po.setToolNames("[]");
        po.setMcpServers("[\"easy-mysql\"]");
        when(manager.findScope(SID)).thenReturn(po);

        SessionScopeView view = service.getScope(SID);

        assertThat(view.kbProjects()).containsExactly("订单域", "物流域");
        assertThat(view.kbTags()).isNull();
        assertThat(view.toolNames()).isEmpty();
        assertThat(view.mcpServers()).containsExactly("easy-mysql");
    }

    @Test
    void getScope_badJson_degradesToEmptyList() {
        ChatSessionScopePO po = new ChatSessionScopePO();
        po.setSessionId(SID);
        po.setKbProjects("not-a-json-array");
        when(manager.findScope(SID)).thenReturn(po);

        SessionScopeView view = service.getScope(SID);

        assertThat(view.kbProjects()).isEmpty();
    }

    @Test
    void getScope_sessionMissing_throws404() {
        when(manager.findSession(SID)).thenReturn(null);

        assertThatThrownBy(() -> service.getScope(SID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ---------- 写 ----------

    @Test
    void updateScope_threeStatePersistedByteExact_looseNormalizeToolNames() {
        SessionScopeUpdate update = new SessionScopeUpdate(
                List.of("订单域"), null,
                List.of(" b ", "a", "", "a"), List.of());

        SessionScopeView view = service.updateScope(SID, update);

        ArgumentCaptor<ChatSessionScopePO> cap = ArgumentCaptor.forClass(ChatSessionScopePO.class);
        verify(manager).upsertScope(cap.capture());
        ChatSessionScopePO po = cap.getValue();
        assertThat(po.getSessionId()).isEqualTo(SID);
        // null → 列 NULL；[] → '[]'；非空 → JSON；toolNames trim/去空/去重保序
        assertThat(po.getKbProjects()).isEqualTo("[\"订单域\"]");
        assertThat(po.getKbTags()).isNull();
        assertThat(po.getToolNames()).isEqualTo("[\"b\",\"a\"]");
        assertThat(po.getMcpServers()).isEqualTo("[]");
        // 返回视图与落库值同构
        assertThat(view.kbProjects()).containsExactly("订单域");
        assertThat(view.kbTags()).isNull();
        assertThat(view.toolNames()).containsExactly("b", "a");
        assertThat(view.mcpServers()).isEmpty();
    }

    @Test
    void updateScope_nullBody_writesAllNulls() {
        SessionScopeView view = service.updateScope(SID, null);

        ArgumentCaptor<ChatSessionScopePO> cap = ArgumentCaptor.forClass(ChatSessionScopePO.class);
        verify(manager).upsertScope(cap.capture());
        assertThat(cap.getValue().getKbProjects()).isNull();
        assertThat(cap.getValue().getKbTags()).isNull();
        assertThat(cap.getValue().getToolNames()).isNull();
        assertThat(cap.getValue().getMcpServers()).isNull();
        assertThat(view.kbProjects()).isNull();
    }

    @Test
    void updateScope_invalidKbProject_throws400() {
        SessionScopeUpdate update = new SessionScopeUpdate(
                List.of("含,逗号"), null, null, null);

        assertThatThrownBy(() -> service.updateScope(SID, update))
                .isInstanceOf(InvalidKbFilterException.class)
                .hasMessageContaining("知识库过滤参数非法");
    }

    @Test
    void updateScope_sessionMissing_throws404() {
        when(manager.findSession(SID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateScope(SID,
                new SessionScopeUpdate(null, null, null, null)))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
