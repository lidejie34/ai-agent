package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.ChatNotConfiguredException;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.ModelCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3/T5（服务侧）：mock {@link ChatModel}，用 {@link ChatClient#create(ChatModel)}
 * 真实装配 ChatClient —— 消息组装、Prompt 构建走真实代码，仅模型调用被 mock。
 * 全程无 Key/无外网（apiKey 用测试桩值，模型层 mock，不发起真实 HTTP）。
 */
class ChatServiceTest {

    private ChatModel chatModel;
    private ChatService service;
    private ChatService unconfiguredService;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ChatClient chatClient = ChatClient.create(chatModel);
        service = new ChatService(chatClient, "ark-test-key", "configured-model");
        unconfiguredService = new ChatService(chatClient, "  ", "configured-model");
    }

    private org.springframework.ai.chat.model.ChatResponse aiResponse(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(text))));
    }

    private org.springframework.ai.chat.model.ChatResponse aiResponse(String text, String model) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).build());
    }

    private ChatRequest request(String message, ChatMessage... history) {
        return new ChatRequest(message, history.length == 0 ? null : List.of(history));
    }

    // ---------- T3 同步主路径 ----------

    @Test
    void sync_call_returnsReplyAndFallsBackToConfiguredModel() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("模拟回答"));

        ChatResponse result = service.chat(request("你好"));

        assertThat(result.reply()).isEqualTo("模拟回答");
        // mock 的 ChatResponse 无 metadata 模型信息时回退到配置模型
        assertThat(result.model()).isEqualTo("configured-model");
    }

    @Test
    void sync_call_prefersModelFromResponseMetadata() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("回答", "ark-model-from-metadata"));

        ChatResponse result = service.chat(request("你好"));

        assertThat(result.model()).isEqualTo("ark-model-from-metadata");
    }

    @Test
    void sync_historyAssembledInOrder_userAssistantCurrentUser() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("你叫小明"));

        service.chat(request("那我刚才说我叫什么？",
                new ChatMessage("user", "你好，我叫小明"),
                new ChatMessage("assistant", "你好小明，很高兴认识你！")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();

        assertThat(instructions).hasSize(3);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(0).getText()).isEqualTo("你好，我叫小明");
        assertThat(instructions.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(instructions.get(1).getText()).isEqualTo("你好小明，很高兴认识你！");
        assertThat(instructions.get(2).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(2).getText()).isEqualTo("那我刚才说我叫什么？");
    }

    @Test
    void sync_noHistory_singleUserMessage() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("ok"));

        service.chat(request("就一句"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(0).getText()).isEqualTo("就一句");
    }

    // ---------- T3 入参校验 ----------

    @Test
    void sync_blankMessage_throwsBadRequest_andModelNotCalled() {
        assertThatThrownBy(() -> service.chat(request("   ")))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("message");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void sync_illegalRole_throwsBadRequest() {
        assertThatThrownBy(() -> service.chat(request("你好",
                new ChatMessage("system", "你是助手"))))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("role");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void sync_blankHistoryContent_throwsBadRequest() {
        assertThatThrownBy(() -> service.chat(request("你好",
                new ChatMessage("user", "  "))))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("content");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ---------- T5 缺 Key ----------

    @Test
    void sync_blankApiKey_throwsNotConfigured_andModelNeverCalled() {
        assertThatThrownBy(() -> unconfiguredService.chat(request("你好")))
                .isInstanceOf(ChatNotConfiguredException.class)
                .hasMessageContaining("ARK_API_KEY");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void sync_placeholderApiKey_throwsNotConfigured() {
        ChatService placeholderService =
                new ChatService(ChatClient.create(chatModel),
                        ChatService.UNCONFIGURED_KEY_PLACEHOLDER, "configured-model");
        assertThatThrownBy(() -> placeholderService.chat(request("你好")))
                .isInstanceOf(ChatNotConfiguredException.class);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ---------- T5 模型调用异常 ----------

    @Test
    void sync_modelThrows_wrappedAsModelCallException_noLeak() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("upstream 500 secret-token"));

        assertThatThrownBy(() -> service.chat(request("你好")))
                .isInstanceOf(ModelCallException.class)
                .hasMessageNotContaining("secret-token")
                .satisfies(e -> assertThat(e.getCause().getMessage()).contains("upstream 500"));
    }

    // ---------- T4/T5 流式 ----------

    @Test
    void stream_emitsChunksInOrder_andCompletes() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(aiResponse("你"), aiResponse("好")));

        // 迭代3：chatStream 返回 ChatStreamResult(sessionId, chunks)；无状态请求 sessionId=null
        StepVerifier.create(service.chatStream(request("说你好")).chunks())
                .expectNext("你")
                .expectNext("好")
                .verifyComplete();
    }

    @Test
    void stream_historyAssembledInOrder() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(aiResponse("你"), aiResponse("叫"), aiResponse("小明")));

        service.chatStream(request("我叫什么？",
                new ChatMessage("user", "我叫小明"))).chunks().collectList().block();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(2);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(0).getText()).isEqualTo("我叫小明");
        assertThat(instructions.get(1).getText()).isEqualTo("我叫什么？");
    }

    @Test
    void stream_modelError_mappedToModelCallException() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("stream boom secret")));

        StepVerifier.create(service.chatStream(request("说你好")).chunks())
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ModelCallException.class);
                    assertThat(e.getMessage()).doesNotContain("secret");
                })
                .verify();
    }

    @Test
    void stream_blankApiKey_throwsNotConfigured_andModelNeverCalled() {
        assertThatThrownBy(() -> unconfiguredService.chatStream(request("你好")))
                .isInstanceOf(ChatNotConfiguredException.class);
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void stream_illegalRole_throwsBadRequest_andModelNeverCalled() {
        assertThatThrownBy(() -> service.chatStream(request("你好",
                new ChatMessage("boss", "hi"))))
                .isInstanceOf(InvalidChatRequestException.class);
        verify(chatModel, never()).stream(any(Prompt.class));
    }
}
