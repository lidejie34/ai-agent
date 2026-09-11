package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.orchestration.OrchEventBridge;
import com.dj.ai.agentchat.orchestration.OrchInput;
import com.dj.ai.agentchat.orchestration.OrchSyncOutcome;
import com.dj.ai.agentchat.orchestration.OrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5：ChatService 主链接入编排（AC-1~6/AC-10/AC-27~30/AC-55）。
 *
 * <p>开关关闭/bean 缺席 → 迭代4 路径零编排交互；sdd:false → 普通路径；
 * sdd 缺省/true 且 bean 在场 → 委托 OrchestrationService（流/同步）；
 * OrchInput 携带历史/会话/预算/共享 mount；编排输出聚合后复用既有成对落库；
 * 降级 supplier 复用迭代4 模型管线（fresh spec、可出内容）。
 */
class ChatServiceOrchestrationTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private OrchestrationService orchestration;
    private ConversationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.tools(anyList())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("普通", "回复"));
        when(callSpec.chatResponse()).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage("普通回复"))),
                ChatResponseMetadata.builder().model("normal-model").build()));
        orchestration = mock(OrchestrationService.class);
        when(orchestration.totalBudget(anyBoolean())).thenReturn(Duration.ofSeconds(110));
        store = mock(ConversationStore.class);
    }

    private ChatService serviceWith(OrchestrationService orch, ConversationStore convStore) {
        return new ChatService(chatClient, "ark-test-key", "cfg-model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                new ChatService.FixedObjectProvider<>(convStore), 20, true,
                new ChatService.FixedObjectProvider<>(null),
                new ChatService.FixedObjectProvider<>(orch),
                null);
    }

    private static ChatRequest request(Boolean sdd, String sessionId) {
        return new ChatRequest("帮我分析日志",
                List.of(new ChatMessage("user", "你好"), new ChatMessage("assistant", "你好呀")),
                sessionId, sdd);
    }

    // ---- 1. bean 缺席（开关关闭）：迭代4 流式路径，零编排交互、无 orchBridge ----

    @Test
    void stream_orchBeanAbsent_iteration4Path_noOrchBridge() {
        ChatService service = serviceWith(null, null);

        ChatStreamResult result = service.chatStream(request(null, null));

        assertThat(result.orchBridge()).isNull();
        assertThat(result.toolBridge()).isNull();
        List<String> chunks = result.chunks().collectList().block(Duration.ofSeconds(5));
        assertThat(String.join("", chunks)).isEqualTo("普通回复");
        verify(orchestration, never()).streamTurn(any(), any());
    }

    // ---- 2. bean 缺席 + sdd:true：warn 忽略（gate 测试已断言 warn），仍走普通路径 ----

    @Test
    void stream_sddTrueButBeanAbsent_normalPath() {
        ChatService service = serviceWith(null, null);

        ChatStreamResult result = service.chatStream(request(Boolean.TRUE, null));

        assertThat(result.orchBridge()).isNull();
        List<String> chunks = result.chunks().collectList().block(Duration.ofSeconds(5));
        assertThat(String.join("", chunks)).isEqualTo("普通回复");
    }

    // ---- 3. bean 在场 + sdd:false：强制普通路径（AC-5） ----

    @Test
    void stream_sddFalse_forcesNormalPath() {
        ChatService service = serviceWith(orchestration, null);

        ChatStreamResult result = service.chatStream(request(Boolean.FALSE, null));

        assertThat(result.orchBridge()).isNull();
        verify(orchestration, never()).streamTurn(any(), any());
        List<String> chunks = result.chunks().collectList().block(Duration.ofSeconds(5));
        assertThat(String.join("", chunks)).isEqualTo("普通回复");
    }

    // ---- 4. bean 在场 + sdd 缺省：委托编排；OrchInput 字段正确，orchBridge 非空 ----

    @Test
    void stream_orchDelegated_orchInputFieldsCorrect_andChunksFromOrch() {
        when(orchestration.streamTurn(any(), any())).thenReturn(Flux.just("汇总", "答案"));
        ChatService service = serviceWith(orchestration, null);

        ChatStreamResult result = service.chatStream(request(null, null));

        assertThat(result.orchBridge()).isNotNull();
        ArgumentCaptor<OrchInput> inCap = ArgumentCaptor.forClass(OrchInput.class);
        verify(orchestration).streamTurn(inCap.capture(), any(OrchEventBridge.class));
        OrchInput in = inCap.getValue();
        assertThat(in.runId()).isNotBlank();
        assertThat(in.userText()).isEqualTo("帮我分析日志");
        assertThat(in.history()).hasSize(2);
        assertThat(in.sessionId()).isNull();
        assertThat(in.streaming()).isTrue();
        assertThat(in.totalBudget()).isEqualTo(Duration.ofSeconds(110));
        // 无工具挂载（toolSupport 缺席）
        assertThat(in.toolMount()).isNull();
        List<String> chunks = result.chunks().collectList().block(Duration.ofSeconds(5));
        assertThat(String.join("", chunks)).isEqualTo("汇总答案");
    }

    // ---- 5. 同步：bean 在场委托 syncTurn；model 透传；有状态成对落库（AC-29/AC-30） ----

    @Test
    void sync_orchDelegated_pairedPersist_modelPassedThrough() {
        when(orchestration.syncTurn(any())).thenReturn(new OrchSyncOutcome("最终答案", "orch-model"));
        ChatService service = serviceWith(orchestration, store);

        ChatResponse response = service.chat(request(null, ""));

        assertThat(response.reply()).isEqualTo("最终答案");
        assertThat(response.model()).isEqualTo("orch-model");
        assertThat(response.sessionId()).isNotBlank();
        verify(orchestration).syncTurn(any(OrchInput.class));
        // 成对落库：seed(2) + user + assistant
        ArgumentCaptor<List<Message>> msgsCap = ArgumentCaptor.forClass(List.class);
        verify(store).add(any(String.class), msgsCap.capture());
        List<Message> persisted = msgsCap.getValue();
        assertThat(persisted).hasSize(4);
        assertThat(persisted.get(persisted.size() - 1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) persisted.get(persisted.size() - 1)).getText())
                .isEqualTo("最终答案");
        assertThat(persisted.get(persisted.size() - 2)).isInstanceOf(UserMessage.class);
    }

    // ---- 6. 流式编排：聚合最终答案成对落库（aggregated=最终答案纯文本，AC-55） ----

    @Test
    void stream_orchChunks_aggregatedAndPersisted() {
        when(orchestration.streamTurn(any(), any())).thenReturn(Flux.just("汇总", "答案"));
        ChatService service = serviceWith(orchestration, store);

        ChatStreamResult result = service.chatStream(request(null, ""));
        result.chunks().blockLast(Duration.ofSeconds(5));

        ArgumentCaptor<List<Message>> msgsCap = ArgumentCaptor.forClass(List.class);
        verify(store).add(any(String.class), msgsCap.capture());
        List<Message> persisted = msgsCap.getValue();
        assertThat(persisted).hasSize(4);
        assertThat(((AssistantMessage) persisted.get(persisted.size() - 1)).getText())
                .isEqualTo("汇总答案");
    }

    // ---- 7. 降级 supplier：路由失败时复用迭代4 模型管线（fresh spec 出内容） ----

    @Test
    void degradeSuppliers_reuseIteration4Pipeline() {
        // 流式编排内部捕获 OrchInput，测试直接调 degrade supplier
        when(orchestration.streamTurn(any(), any())).thenAnswer(inv -> {
            OrchInput in = inv.getArgument(0);
            return in.degradeStream().get();
        });
        when(orchestration.syncTurn(any())).thenAnswer(inv -> {
            OrchInput in = inv.getArgument(0);
            return new OrchSyncOutcome(in.degradeCall().get(), null);
        });
        ChatService service = serviceWith(orchestration, null);

        ChatStreamResult streamResult = service.chatStream(request(null, null));
        List<String> chunks = streamResult.chunks().collectList().block(Duration.ofSeconds(5));
        assertThat(String.join("", chunks)).isEqualTo("普通回复");

        ChatResponse response = service.chat(request(null, null));
        assertThat(response.reply()).isEqualTo("普通回复");
    }
}
