package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryPersistException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.memory.ConversationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4：同步链路记忆编排（AC-1/3/6/8/11/12/13/14/15/17/18 同步部分）。
 * mock {@link ConversationStore} + {@code ChatClient.create(mock(ChatModel.class))}，
 * 全程离线；三态判定、UUID 生成/校验、seed-once、成对落库、异常分支。
 */
class ChatServiceMemorySyncTest {

    private ChatModel chatModel;
    private ConversationStore store;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        store = mock(ConversationStore.class);
        service = new ChatService(ChatClient.create(chatModel), "ark-test-key",
                "configured-model", store, 20);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("模拟回答")))));
    }

    private ChatRequest stateless(String message) {
        return new ChatRequest(message, null, null);
    }

    // ---------- 无状态：零 store 交互、sessionId 为 null（AC-1） ----------

    @Test
    void stateless_noStoreInteractions_andNullSessionId() {
        ChatResponse response = service.chat(stateless("你好"));

        assertThat(response.sessionId()).isNull();
        verifyNoInteractions(store);
    }

    @Test
    void stateless_withHistory_stillNoStoreInteractions() {
        ChatRequest request = new ChatRequest("我叫什么？",
                List.of(new ChatMessage("user", "我叫小明")), null);

        service.chat(request);

        verifyNoInteractions(store);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        // 无状态下 history 仍按迭代2 行为进 Prompt
        assertThat(captor.getValue().getInstructions()).hasSize(2);
    }

    // ---------- 首轮新建（sessionId=""）：服务端生成 UUID、成对落库（AC-3/8） ----------

    @Test
    void newSession_emptyString_generatesServerUuid_createsSession_persistsPair() {
        ChatResponse response = service.chat(new ChatRequest("你好", null, ""));

        assertThat(response.sessionId()).isNotNull();
        assertThat(response.sessionId()).hasSize(36);
        assertThatCode(() -> UUID.fromString(response.sessionId())).doesNotThrowAnyException();

        ArgumentCaptor<String> sidCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).createSessionIfAbsent(sidCaptor.capture());
        String sid = sidCaptor.getValue();
        assertThat(sid).isEqualTo(response.sessionId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).add(eq(sid), messagesCaptor.capture());
        List<Message> persisted = messagesCaptor.getValue();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(0).getText()).isEqualTo("你好");
        assertThat(persisted.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(persisted.get(1).getText()).isEqualTo("模拟回答");
    }

    @Test
    void newSession_promptContainsCurrentUserMessage() {
        service.chat(new ChatRequest("首轮问题", null, ""));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.get(0).getText()).isEqualTo("首轮问题");
    }

    // ---------- seed-once：新建带 history → 4 条同批落库、Prompt 含 seed（AC-12） ----------

    @Test
    void newSession_withSeedHistory_persistsFourInOrder_andPromptContainsSeed() {
        ChatRequest request = new ChatRequest("我叫什么？", List.of(
                new ChatMessage("user", "我叫小明"),
                new ChatMessage("assistant", "你好小明")), "");

        ChatResponse response = service.chat(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).add(eq(response.sessionId()), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT,
                        MessageType.USER, MessageType.ASSISTANT);
        assertThat(messagesCaptor.getValue().get(0).getText()).isEqualTo("我叫小明");
        assertThat(messagesCaptor.getValue().get(3).getText()).isEqualTo("模拟回答");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> instructions = promptCaptor.getValue().getInstructions();
        assertThat(instructions).hasSize(3);
        assertThat(instructions.get(0).getText()).isEqualTo("我叫小明");
        assertThat(instructions.get(2).getText()).isEqualTo("我叫什么？");
    }

    // ---------- 续接：历史加载组装、sessionId 回显、忽略请求 history（AC-6/13） ----------

    @Test
    void resume_loadsHistoryInOrder_andEchoesSessionId() {
        String sid = UUID.randomUUID().toString();
        when(store.get(eq(sid), anyInt())).thenReturn(List.of(
                new UserMessage("历史问题"),
                new AssistantMessage("历史回答")));

        ChatResponse response = service.chat(new ChatRequest("本轮追问", null, sid));

        assertThat(response.sessionId()).isEqualTo(sid);
        verify(store).createSessionIfAbsent(sid);
        verify(store).get(sid, 20);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(3);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(0).getText()).isEqualTo("历史问题");
        assertThat(instructions.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(instructions.get(1).getText()).isEqualTo("历史回答");
        assertThat(instructions.get(2).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(2).getText()).isEqualTo("本轮追问");

        // 续接落库只写本轮 user+assistant，不回写历史
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).add(eq(sid), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(2);
    }

    @Test
    void resume_withRequestHistory_historyIgnoredNotPersisted() {
        String sid = UUID.randomUUID().toString();
        when(store.get(eq(sid), anyInt())).thenReturn(List.of());
        ChatRequest request = new ChatRequest("追问",
                List.of(new ChatMessage("user", "请求体携带的历史应被忽略")), sid);

        service.chat(request);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions()).hasSize(1);
        assertThat(captor.getValue().getInstructions().get(0).getText()).isEqualTo("追问");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).add(eq(sid), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(2);
        assertThat(messagesCaptor.getValue().get(0).getText()).isEqualTo("追问");
    }

    // ---------- 异常分支 ----------

    @Test
    void newSession_dbDownAtCreate_throwsMemoryUnavailable() {
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"))
                .when(store).createSessionIfAbsent(anyString());

        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null, "")))
                .isInstanceOf(MemoryUnavailableException.class)
                .hasMessageContaining("暂不可用");
        verify(store, never()).add(anyString(), any(List.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void resume_dbDownAtGet_throwsMemoryUnavailable() {
        String sid = UUID.randomUUID().toString();
        when(store.get(anyString(), anyInt()))
                .thenThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));

        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null, sid)))
                .isInstanceOf(MemoryUnavailableException.class);
        verify(store, never()).add(anyString(), any(List.class));
    }

    @Test
    void illegalSessionId_throwsBadRequest_withoutTouchingStore() {
        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null, "abc")))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null,
                "123e4567-e89b-12d3-a456-426614174000-extra")))
                .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null,
                "123e4567-e89b-12d3-a456-42661417400!")))
                .isInstanceOf(InvalidChatRequestException.class);
        verifyNoInteractions(store);
    }

    @Test
    void modelFailure_doesNotPersist_throwsModelCall() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("upstream 500"));

        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null, "")))
                .isInstanceOf(ModelCallException.class);
        // 会话行已创建（记忆阶段），但消息不落库（FR-11/AC-11）
        verify(store).createSessionIfAbsent(anyString());
        verify(store, never()).add(anyString(), any(List.class));
    }

    @Test
    void persistFailure_throwsMemoryPersist_andReplyNotReturned() {
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("write failed"))
                .when(store).add(anyString(), any(List.class));

        assertThatThrownBy(() -> service.chat(new ChatRequest("你好", null, "")))
                .isInstanceOf(MemoryPersistException.class)
                .hasMessageContaining("会话保存失败");
    }

    // ---------- 配置：maxHistory 透传、记忆关闭 400（AC-18/FR-17） ----------

    @Test
    void maxHistoryConfigured_passedToStoreGet() {
        ChatService service4 = new ChatService(ChatClient.create(chatModel), "ark-test-key",
                "configured-model", store, 4);
        String sid = UUID.randomUUID().toString();
        when(store.get(eq(sid), anyInt())).thenReturn(List.of());

        service4.chat(new ChatRequest("你好", null, sid));

        verify(store).get(sid, 4);
    }

    @Test
    void memoryDisabled_statefulRequest_throwsBadRequest() {
        ChatService disabled = new ChatService(ChatClient.create(chatModel), "ark-test-key",
                "configured-model", null, 20, false);

        assertThatThrownBy(() -> disabled.chat(new ChatRequest("你好", null, "")))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("未启用");
        assertThatThrownBy(() -> disabled.chat(
                new ChatRequest("你好", null, UUID.randomUUID().toString())))
                .isInstanceOf(InvalidChatRequestException.class);

        // 无状态不受影响
        ChatResponse response = disabled.chat(stateless("你好"));
        assertThat(response.sessionId()).isNull();
    }
}
