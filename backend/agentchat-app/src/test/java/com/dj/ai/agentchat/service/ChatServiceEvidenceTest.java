package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.config.ChatEvidenceProperties;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.memory.ToolEvidenceMessage;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolEvidenceCollector;
import com.dj.ai.agentchat.tool.support.ToolMount;
import com.dj.ai.agentchat.tool.support.ToolSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5（迭代8，mock）：ChatService 证据接线——
 * <ul>
 *   <li>开关开 + 本轮有工具调用 → 落库 [user, evidence, assistant] 三条且次序正确；</li>
 *   <li>开关关 → 恰好 2 条，与既有行为逐字节回归；</li>
 *   <li>流式 complete 落证据 / error 不落；</li>
 *   <li>renderEvidence 抛异常 → 仅跳过证据，user/assistant 照常落库；</li>
 *   <li>开关开但本轮无工具调用 → 不落证据。</li>
 * </ul>
 */
class ChatServiceEvidenceTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private ConversationStore store;
    private ToolCallBridge bridge;
    private ToolMount mount;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.tools(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("模拟回答")))));
        when(streamSpec.content()).thenReturn(Flux.just("你", "好"));
        store = mock(ConversationStore.class);

        bridge = new ToolCallBridge();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("requestId", "req-evidence-1");
        ctx.put("toolBridge", bridge);
        mount = new ToolMount(List.of(mock(ToolCallback.class)), ctx, bridge);
    }

    private ChatService service(ChatEvidenceProperties properties, ToolSupport toolSupport) {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                new ChatService.FixedObjectProvider<>(store), 20, true,
                new ChatService.FixedObjectProvider<>(toolSupport),
                null, null, properties);
    }

    private static ChatEvidenceProperties enabledProperties() {
        ChatEvidenceProperties properties = new ChatEvidenceProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static ToolSupport supportReturning(ToolMount mount) {
        ToolSupport support = mock(ToolSupport.class);
        // 迭代12：ChatService 统一走双参重载 mountTools(sessionId, selection)；any() 匹配 null selection
        when(support.mountTools(anyString(), any())).thenReturn(mount);
        return support;
    }

    /** 模拟一次工具执行：经 bridge.remember 写幂等缓存 + 证据（与 Callback 生产路径同构）。 */
    private void simulateToolCall() {
        bridge.remember("req-evidence-1|demo_tool|hash", new ToolCallBridge.CachedOutcome(
                "<tool-result>\n结果正文\n</tool-result>",
                "demo_tool", "SUCCESS", 12L, "{\"uk\":\"123\"}", "结果正文"));
    }

    @SuppressWarnings("unchecked")
    private List<Message> capturedPersist() {
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(store, timeout(2000)).add(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void sync_enabledWithToolCall_persistsUserEvidenceAssistantInOrder() {
        // 工具在「模型调用期间」执行：call 应答里模拟一次工具调用
        when(spec.call()).then(invocation -> {
            simulateToolCall();
            return callSpec;
        });
        ChatService service = service(enabledProperties(), supportReturning(mount));

        service.chat(new ChatRequest("查日志", null, ""));

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(3);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(0).getText()).isEqualTo("查日志");
        assertThat(persisted.get(1)).isInstanceOf(ToolEvidenceMessage.class);
        assertThat(persisted.get(1).getText())
                .contains("demo_tool | SUCCESS | 12ms | 入参: {\"uk\":\"123\"} | 结果: 结果正文");
        assertThat(persisted.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(persisted.get(2).getText()).isEqualTo("模拟回答");
    }

    @Test
    void sync_disabled_persistsExactlyPair_byteEquivalentRegression() {
        when(spec.call()).then(invocation -> {
            simulateToolCall(); // 即使 bridge 上有动作，开关关 → 无收集器 → 零证据
            return callSpec;
        });
        ChatService service = service(new ChatEvidenceProperties(), supportReturning(mount));

        service.chat(new ChatRequest("查日志", null, ""));

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(0).getText()).isEqualTo("查日志");
        assertThat(persisted.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(persisted.get(1).getText()).isEqualTo("模拟回答");
    }

    @Test
    void sync_enabledNoToolCall_noEvidencePersisted() {
        ChatService service = service(enabledProperties(), supportReturning(mount));

        service.chat(new ChatRequest("普通问题", null, ""));

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(2);
        assertThat(persisted).noneMatch(m -> m instanceof ToolEvidenceMessage);
    }

    @Test
    void sync_renderThrowing_skipsEvidenceOnly_pairStillPersisted() {
        ChatEvidenceProperties properties = enabledProperties();
        ToolSupport support = supportReturning(mount);
        ChatService service = new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                new ChatService.FixedObjectProvider<>(store), 20, true,
                new ChatService.FixedObjectProvider<>(support),
                null, null, properties) {
            @Override
            ToolEvidenceCollector newEvidenceCollector() {
                return new ToolEvidenceCollector(800, 4000) {
                    @Override
                    public String renderEvidence() {
                        throw new RuntimeException("模拟渲染故障");
                    }
                };
            }
        };
        when(spec.call()).then(invocation -> {
            simulateToolCall();
            return callSpec;
        });

        service.chat(new ChatRequest("查日志", null, ""));

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }

    @Test
    void stream_complete_persistsEvidenceBetweenUserAndAssistant() {
        when(streamSpec.content()).then(invocation -> {
            simulateToolCall();
            return Flux.just("你", "好");
        });
        ChatService service = service(enabledProperties(), supportReturning(mount));

        StepVerifier.create(service.chatStream(new ChatRequest("查日志", null, "")).chunks())
                .expectNext("你", "好")
                .verifyComplete();

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(3);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(1)).isInstanceOf(ToolEvidenceMessage.class);
        assertThat(persisted.get(1).getText()).contains("demo_tool | SUCCESS");
        assertThat(persisted.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(persisted.get(2).getText()).isEqualTo("你好");
    }

    @Test
    void stream_error_persistsNothing() {
        when(streamSpec.content()).then(invocation -> {
            simulateToolCall();
            return Flux.error(new RuntimeException("模型流中断"));
        });
        ChatService service = service(enabledProperties(), supportReturning(mount));

        StepVerifier.create(service.chatStream(new ChatRequest("查日志", null, "")).chunks())
                .expectError()
                .verify();

        // error/cancel/重试耗尽不触发 doOnComplete → 不落库、不落证据（与既有语义一致）
        verify(store, never()).add(anyString(), anyList());
    }

    @Test
    void stream_disabled_persistsExactlyPairOnComplete() {
        when(streamSpec.content()).then(invocation -> {
            simulateToolCall();
            return Flux.just("你", "好");
        });
        ChatService service = service(new ChatEvidenceProperties(), supportReturning(mount));

        StepVerifier.create(service.chatStream(new ChatRequest("查日志", null, "")).chunks())
                .expectNext("你", "好")
                .verifyComplete();

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }

    @Test
    void sync_enabledNoMount_noEvidencePersisted() {
        // 工具支持缺席（mount=null）→ 短路不建收集器，与无工具路径一致
        ChatService service = service(enabledProperties(), supportReturning(null));

        service.chat(new ChatRequest("普通问题", null, ""));

        List<Message> persisted = capturedPersist();
        assertThat(persisted).hasSize(2);
        assertThat(persisted).noneMatch(m -> m instanceof ToolEvidenceMessage);
    }

    @Test
    void sync_persistTargetsServerIssuedSessionId() {
        // 落库目标会话为服务端新建会话（stateful 路径冒烟）
        ChatService service = service(enabledProperties(), supportReturning(mount));

        var response = service.chat(new ChatRequest("查日志", null, ""));

        verify(store, timeout(2000)).add(eq(response.sessionId()), anyList());
        assertThat(response.sessionId()).hasSize(36);
    }
}
