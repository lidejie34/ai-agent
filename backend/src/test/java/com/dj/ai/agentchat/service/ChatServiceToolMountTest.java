package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolMount;
import com.dj.ai.agentchat.tool.support.ToolSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T8：ChatService 请求级工具挂载（AC-58/60/62/63）——
 * 有工具→.tools(list)+.toolContext(map)（map 含 sessionId/requestId/toolBridge）；
 * 空挂载→不调 .tools（与迭代 F 逐字节等价）；流式挂载在 Flux.defer 外创建，
 * 重试重订阅 requestId 稳定；挂载异常降级无工具。
 */
class ChatServiceToolMountTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;

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
        when(streamSpec.content()).thenReturn(Flux.just("a", "b"));
    }

    private ChatService serviceWith(ToolSupport toolSupport) {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                null, 20, true, new ChatService.FixedObjectProvider<>(toolSupport));
    }

    private ChatService serviceWithStoreAndTools(ConversationStore store, ToolSupport toolSupport) {
        return new ChatService(chatClient, "ark-test-key", "model",
                3, Duration.ofMillis(10), Duration.ofMillis(100),
                new ChatService.FixedObjectProvider<>(store), 20, true,
                new ChatService.FixedObjectProvider<>(toolSupport));
    }

    private ToolSupport supportReturning(ToolMount mount) {
        ToolSupport support = mock(ToolSupport.class);
        when(support.mountTools(org.mockito.ArgumentMatchers.any())).thenReturn(mount);
        return support;
    }

    private ToolMount mount(String sessionId) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolCallBridge bridge = new ToolCallBridge();
        // Spring AI toolContext(Map) 经 Assert.noNullElements 拒绝 null 值：
        // 无状态 sessionId=null 时不放该键（与 DefaultToolSupport 生产行为一致），故用 HashMap
        Map<String, Object> ctx = new java.util.HashMap<>();
        if (sessionId != null) {
            ctx.put("sessionId", sessionId);
        }
        ctx.put("requestId", "req-stable-1");
        ctx.put("toolBridge", bridge);
        return new ToolMount(List.of(callback), ctx, bridge);
    }

    @Test
    void stream_withTools_mountsToolsAndContext() {
        ToolMount mount = mount(null);
        ChatService service = serviceWith(supportReturning(mount));

        var result = service.chatStream(new ChatRequest("你好", null));

        StepVerifier.create(result.chunks()).expectNext("a").expectNext("b").verifyComplete();

        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(spec).tools(toolsCaptor.capture());
        assertThat(toolsCaptor.getValue()).containsExactlyElementsOf(mount.callbacks());

        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(ctxCaptor.capture());
        Map<String, Object> ctx = ctxCaptor.getValue();
        // 无状态：sessionId 键缺失而非 null（Spring AI toolContext 拒绝 null value）
        assertThat(ctx).doesNotContainKey("sessionId");
        assertThat(ctx.get("requestId")).isEqualTo("req-stable-1");
        assertThat(ctx.get("toolBridge")).isSameAs(mount.bridge());
        // 上下文中不得出现任何 null 值（DefaultChatClientRequestSpec.toolContext noNullElements）
        assertThat(ctx.values()).noneMatch(java.util.Objects::isNull);
        // 无工具挂载时 guide 不进上下文（guide 只在工具结果内，S2 渐进披露）
        assertThat(ctx).doesNotContainKey("guide");
        // ChatStreamResult 携带同一 bridge，供控制器 setSink/detach
        assertThat(result.toolBridge()).isSameAs(mount.bridge());
    }

    @Test
    void stream_stateful_sessionIdPassedToMountAndContext() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        String sid = "550e8400-e29b-41d4-a716-446655440000";
        ToolSupport support = mock(ToolSupport.class);
        ToolMount mount = mount(sid);
        when(support.mountTools(sid)).thenReturn(mount);
        ChatService service = serviceWithStoreAndTools(store, support);

        service.chatStream(new ChatRequest("你好", null, sid)).chunks().blockLast();

        verify(support).mountTools(sid);
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().get("sessionId")).isEqualTo(sid);
    }

    @Test
    void stream_emptyMount_doesNotCallTools_byteEquivalentToBaseline() {
        ChatService service = serviceWith(supportReturning(null));

        StepVerifier.create(service.chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("a").expectNext("b").verifyComplete();

        verify(spec, never()).tools(anyList());
        verify(spec, never()).toolContext(anyMap());
    }

    @Test
    void stream_mountThrowing_degradesToNoTools() {
        ToolSupport support = mock(ToolSupport.class);
        when(support.mountTools(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("注册中心炸了"));
        ChatService service = serviceWith(support);

        StepVerifier.create(service.chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("a").expectNext("b").verifyComplete();

        verify(spec, never()).tools(anyList());
    }

    @Test
    void stream_noToolSupportBean_byteEquivalentToBaseline() {
        // 便捷构造：ObjectProvider<ToolSupport>=null（app.tools.enabled=false 语义）
        ChatService service = new ChatService(chatClient, "ark-test-key", "model");

        StepVerifier.create(service.chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("a").expectNext("b").verifyComplete();

        verify(spec, never()).tools(anyList());
        verify(spec, never()).toolContext(anyMap());
    }

    @Test
    void stream_resubscription_reusesSameMount_requestIdStable() throws Exception {
        // 首次订阅抛 IO 类故障（首片段前可重试）→ 重订阅第二次成功；
        // 两次 toolContext 的 requestId/bridge 必须一致（挂载在 Flux.defer 外创建）
        ChatClient.StreamResponseSpec second = mock(ChatClient.StreamResponseSpec.class);
        when(second.content()).thenReturn(Flux.just("ok"));
        when(spec.stream())
                .thenThrow(new RuntimeException(new java.io.IOException("connection reset by peer")))
                .thenReturn(second);

        ToolMount mount = mount(null);
        ChatService service = serviceWith(supportReturning(mount));

        StepVerifier.create(service.chatStream(new ChatRequest("你好", null)).chunks())
                .expectNext("ok").verifyComplete();

        // 重试导致两次 defer 装配 → toolContext 调两次，但挂载只创建一次
        verify(spec, times(2)).toolContext(anyMap());
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(spec, times(2)).toolContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getAllValues().get(0).get("requestId"))
                .isEqualTo(ctxCaptor.getAllValues().get(1).get("requestId"));
        assertThat(ctxCaptor.getAllValues().get(0).get("toolBridge"))
                .isSameAs(ctxCaptor.getAllValues().get(1).get("toolBridge"));
    }

    @Test
    void sync_withTools_mountsToolsAndContext() {
        ToolMount mount = mount(null);
        ChatService service = serviceWith(supportReturning(mount));

        service.chat(new ChatRequest("你好", null));

        verify(spec).tools(anyList());
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().get("toolBridge")).isSameAs(mount.bridge());
    }

    @Test
    void sync_noTools_doesNotCallTools() {
        ChatService service = serviceWith(supportReturning(null));

        service.chat(new ChatRequest("你好", null));

        verify(spec, never()).tools(anyList());
        verify(spec, never()).toolContext(anyMap());
    }
}
