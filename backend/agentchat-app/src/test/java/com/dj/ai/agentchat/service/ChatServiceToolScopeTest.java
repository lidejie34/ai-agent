package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.config.ChatEvidenceProperties;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;
import com.dj.ai.agentchat.tool.support.ToolSelection;
import com.dj.ai.agentchat.tool.support.ToolSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代12：ChatService 对话级工具选择解析 + 会话级范围配置 best-effort upsert——
 * resolveToolSelection 三态规整（双 null→null、trim/去重、空数组保留）；
 * persistSessionScope 仅有状态轮且携带 scope 字段时 upsert（无状态零交互），
 * 异常仅 warn 不阻断对话（NFR-2）。
 */
class ChatServiceToolScopeTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ConversationStore store;
    private SessionManager sessionManager;
    private ToolSupport toolSupport;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.tools(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));
        when(streamSpec.content()).thenReturn(Flux.just("a"));
        store = mock(ConversationStore.class);
        when(store.get(anyString(), anyInt())).thenReturn(List.of());
        sessionManager = mock(SessionManager.class);
        toolSupport = mock(ToolSupport.class);
    }

    private ChatService service() {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                new ChatService.FixedObjectProvider<>(store), 20, true,
                new ChatService.FixedObjectProvider<>(toolSupport),
                null, null, new ChatEvidenceProperties(), null,
                new ChatService.FixedObjectProvider<>(sessionManager));
    }

    // ---------- resolveToolSelection ----------

    @Test
    void chat_withToolNames_normalizedSelectionPassedToMount() {
        service().chat(new ChatRequest("查日志", null, "", null, null, null,
                List.of(" b ", "a", "", "a"), null));

        ArgumentCaptor<ToolSelection> cap = ArgumentCaptor.forClass(ToolSelection.class);
        verify(toolSupport).mountTools(anyString(), cap.capture());
        // trim/去空/去重保序；mcpServers 未携带 → null（该侧全量）
        assertThat(cap.getValue().toolNames()).containsExactly("b", "a");
        assertThat(cap.getValue().mcpServers()).isNull();
    }

    @Test
    void chat_noScopeFields_nullSelectionAndNoUpsert() {
        service().chat(new ChatRequest("你好", null, ""));

        verify(toolSupport).mountTools(anyString(), isNull());
        verify(sessionManager, never()).upsertScope(any());
    }

    @Test
    void chat_emptyArrays_preservedAsExplicitNone() {
        service().chat(new ChatRequest("你好", null, "", null, null, null,
                List.of(), List.of()));

        ArgumentCaptor<ToolSelection> cap = ArgumentCaptor.forClass(ToolSelection.class);
        verify(toolSupport).mountTools(anyString(), cap.capture());
        assertThat(cap.getValue().toolNames()).isEmpty();
        assertThat(cap.getValue().mcpServers()).isEmpty();
    }

    // ---------- persistSessionScope ----------

    @Test
    void chat_statefulWithScopeFields_upsertsScopePo() {
        var response = service().chat(new ChatRequest("查售后", null, "", null,
                List.of("订单域"), List.of("售后"), List.of("t1"), null));

        ArgumentCaptor<ChatSessionScopePO> cap = ArgumentCaptor.forClass(ChatSessionScopePO.class);
        verify(sessionManager).upsertScope(cap.capture());
        ChatSessionScopePO po = cap.getValue();
        // 落库目标 = 服务端确立的会话 ID（首轮绑定）
        assertThat(po.getSessionId()).isEqualTo(response.sessionId());
        assertThat(po.getKbProjects()).isEqualTo("[\"订单域\"]");
        assertThat(po.getKbTags()).isEqualTo("[\"售后\"]");
        assertThat(po.getToolNames()).isEqualTo("[\"t1\"]");
        // 未携带字段 → 列 NULL（默认全部），三态保持
        assertThat(po.getMcpServers()).isNull();
    }

    @Test
    void chat_stateless_neverTouchesScopeConfig() {
        service().chat(new ChatRequest("查日志", null, null, null, null, null,
                List.of("t1"), null));

        verify(sessionManager, never()).upsertScope(any());
        // 无状态轮工具选择仍然生效（请求驱动，NFR-1）
        ArgumentCaptor<ToolSelection> cap = ArgumentCaptor.forClass(ToolSelection.class);
        verify(toolSupport).mountTools(isNull(), cap.capture());
        assertThat(cap.getValue().toolNames()).containsExactly("t1");
    }

    @Test
    void chat_upsertThrows_bestEffortContinues() {
        doThrow(new RuntimeException("db down")).when(sessionManager).upsertScope(any());

        assertThatCode(() -> service().chat(new ChatRequest("查日志", null, "", null, null, null,
                List.of("t1"), null)))
                .doesNotThrowAnyException();
        // 对话主流程不受影响
        verify(spec).call();
    }
}
