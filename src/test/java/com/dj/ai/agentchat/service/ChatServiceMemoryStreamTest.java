package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.ChatMessage;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.memory.ConversationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T5：流式链路记忆编排（AC-2/4/5/7/9/10/15/17 流式部分）。
 *
 * <p>记忆阶段在 service 同步段完成（返回/订阅 Flux 前）：新建/续接/DB 探测失败同步抛出；
 * 正常 complete 后在 boundedElastic 线程成对落库（聚合全文），失败仅吞掉日志、done 不丢；
 * error/cancel/重试耗尽均不落库、不留孤儿 user。
 */
class ChatServiceMemoryStreamTest {

    private ChatModel chatModel;
    private ConversationStore store;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        store = mock(ConversationStore.class);
        service = new ChatService(ChatClient.create(chatModel), "ark-test-key",
                "configured-model", store, 20);
    }

    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private WebClientResponseException httpError(int status) {
        return WebClientResponseException.create(status, "HTTP " + status,
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }

    // ---------- 无状态：零 store 交互、sessionId 为 null（AC-2） ----------

    @Test
    void stateless_resultHasNullSessionId_andNoStoreInteractions() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("你"), chunk("好")));

        ChatStreamResult result = service.chatStream(new ChatRequest("说你好", null, null));

        assertThat(result.sessionId()).isNull();
        StepVerifier.create(result.chunks())
                .expectNext("你", "好")
                .verifyComplete();
        verifyNoInteractions(store);
    }

    // ---------- 首轮新建：服务端 UUID + complete 后聚合全文成对落库（AC-4/9） ----------

    @Test
    void newSession_returnsServerUuid_andPersistsAggregatedPairOnComplete() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("你"), chunk("好"), chunk("，世界")));

        ChatStreamResult result = service.chatStream(new ChatRequest("打招呼", null, ""));

        assertThat(result.sessionId()).isNotNull();
        assertThatCode(() -> UUID.fromString(result.sessionId())).doesNotThrowAnyException();
        StepVerifier.create(result.chunks())
                .expectNext("你", "好", "，世界")
                .verifyComplete();

        // complete 后 boundedElastic 异步落库：等待并断言成对、assistant 为聚合全文
        verify(store, timeout(2000)).createSessionIfAbsent(eq(result.sessionId()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(store, timeout(2000)).add(eq(result.sessionId()), captor.capture());
        List<Message> persisted = captor.getValue();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(persisted.get(0).getText()).isEqualTo("打招呼");
        assertThat(persisted.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(persisted.get(1).getText()).isEqualTo("你好，世界");
    }

    // ---------- 续接：历史进 Prompt、sid 回显、落库仅本轮（AC-7） ----------

    @Test
    void resume_echoesSessionId_loadsHistory_persistsOnlyTurn() {
        String sid = UUID.randomUUID().toString();
        when(store.get(eq(sid), anyInt())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("回答")));

        ChatStreamResult result = service.chatStream(new ChatRequest("追问", null, sid));

        assertThat(result.sessionId()).isEqualTo(sid);
        StepVerifier.create(result.chunks()).expectNext("回答").verifyComplete();
        verify(store, timeout(2000)).add(eq(sid), any(List.class));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions()).hasSize(1);
    }

    // ---------- 失败/中断不落库（AC-5/10） ----------

    @Test
    void modelErrorBeforeFirstChunk_retriesExhausted_doesNotPersist() {
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> Flux.error(httpError(500)));

        ChatStreamResult result = service.chatStream(new ChatRequest("你好", null, ""));
        String sid = result.sessionId();

        StepVerifier.create(result.chunks())
                .expectError()
                .verify();

        // 会话行已建（记忆阶段），但消息一条不落（重试耗尽无片段）
        verify(store).createSessionIfAbsent(eq(sid));
        verify(store, never()).add(anyString(), any(List.class));
    }

    @Test
    void modelErrorAfterFirstChunk_doesNotPersist() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(chunk("片段一")), Flux.error(httpError(503))));

        ChatStreamResult result = service.chatStream(new ChatRequest("你好", null, ""));

        StepVerifier.create(result.chunks())
                .expectNext("片段一")
                .expectError()
                .verify();

        verify(store, never()).add(anyString(), any(List.class));
    }

    @Test
    void clientCancel_doesNotPersist() {
        // 不发 complete 的热流：客户端取消后不触发 doOnComplete
        reactor.core.publisher.Sinks.Many<ChatResponse> sink =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(sink.asFlux());

        ChatStreamResult result = service.chatStream(new ChatRequest("慢慢说", null, ""));
        sink.tryEmitNext(chunk("片段"));

        StepVerifier.create(result.chunks())
                .expectNext("片段")
                .thenCancel()
                .verify();

        verify(store, never()).add(anyString(), any(List.class));
    }

    // ---------- 流式落库失败：Flux 仍正常 complete（AC-17，done 不丢） ----------

    @Test
    void persistFailureOnComplete_fluxStillCompletes_andDoneNotLost() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答")));
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("write failed"))
                .when(store).add(anyString(), any(List.class));

        ChatStreamResult result = service.chatStream(new ChatRequest("你好", null, ""));

        // 落库异常被 doOnComplete 内部吞掉：complete 信号不转为 error
        StepVerifier.create(result.chunks())
                .expectNext("完整回答")
                .verifyComplete();
        verify(store, timeout(2000)).add(anyString(), any(List.class));
    }

    // ---------- 记忆阶段失败：订阅前同步抛出（AC-15） ----------

    @Test
    void memoryPhaseFailure_throwsSynchronouslyBeforeFlux() {
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionException("DB down"))
                .when(store).createSessionIfAbsent(anyString());

        assertThatThrownBy(() -> service.chatStream(new ChatRequest("你好", null, "")))
                .isInstanceOf(MemoryUnavailableException.class);
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void illegalSessionId_throwsSynchronously_withoutStoreTouch() {
        assertThatThrownBy(() -> service.chatStream(new ChatRequest("你好", null, "not-a-uuid")))
                .isInstanceOf(InvalidChatRequestException.class);
        verifyNoInteractions(store);
    }

    @Test
    void resumeWithSeedHistory_historyIgnored() {
        String sid = UUID.randomUUID().toString();
        when(store.get(eq(sid), anyInt())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("好")));

        ChatStreamResult result = service.chatStream(new ChatRequest("追问",
                List.of(new ChatMessage("user", "请求体历史应忽略")), sid));

        StepVerifier.create(result.chunks()).expectNext("好").verifyComplete();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        assertThat(captor.getValue().getInstructions()).hasSize(1);
        assertThat(captor.getValue().getInstructions().get(0).getText()).isEqualTo("追问");
    }
}
