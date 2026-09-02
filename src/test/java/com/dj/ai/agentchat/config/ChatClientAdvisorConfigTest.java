package com.dj.ai.agentchat.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dj.ai.agentchat.advisor.RequestLoggingAdvisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2/T5：{@link ChatClientConfig} 装配断言——默认 system prompt 配置生效/缺省不改变消息结构（AC-3/AC-4），
 * 默认 Advisor 链挂载日志 Advisor（AC-6）。
 *
 * <p>手法：用真实 {@link ChatClient.Builder} + mock {@link ChatModel} 装配（Advisor 链走真实代码，
 * 仅模型终调被 mock），ArgumentCaptor 捕获发往模型的 Prompt 断言消息结构。
 */
class ChatClientAdvisorConfigTest {

    private ChatModel chatModel;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger advisorLogger;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        advisorLogger = (Logger) LoggerFactory.getLogger(RequestLoggingAdvisor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        advisorLogger.addAppender(logAppender);
        advisorLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        advisorLogger.detachAppender(logAppender);
    }

    private ChatClient buildClient(String systemPrompt) {
        return new ChatClientConfig().chatClient(
                ChatClient.builder(chatModel),
                new RequestLoggingAdvisor("configured-model"),
                systemPrompt);
    }

    private org.springframework.ai.chat.model.ChatResponse aiResponse(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(text))));
    }

    // ---------- T2：system prompt 装配（AC-3） ----------

    @Test
    void nonBlankSystemPrompt_callPath_injectsSystemMessage() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("ok"));
        ChatClient client = buildClient("你是一个惜字如金的助手");

        client.prompt().user("说你好").call().chatResponse();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).anyMatch(m -> m.getMessageType() == MessageType.SYSTEM
                && "你是一个惜字如金的助手".equals(m.getText()));
        assertThat(instructions).anyMatch(m -> m.getMessageType() == MessageType.USER);
    }

    @Test
    void nonBlankSystemPrompt_streamPath_injectsSystemMessage() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(aiResponse("你"), aiResponse("好")));
        ChatClient client = buildClient("你是一个惜字如金的助手");

        client.prompt().user("说你好").stream().content().collectList().block();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).anyMatch(m -> m.getMessageType() == MessageType.SYSTEM
                && "你是一个惜字如金的助手".equals(m.getText()));
    }

    // ---------- T2：缺省不改变行为（AC-4） ----------

    @Test
    void blankSystemPrompt_callPath_noSystemMessage_sameAsIteration1() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("ok"));
        ChatClient client = buildClient("   ");

        client.prompt().user("就一句").call().chatResponse();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(0).getText()).isEqualTo("就一句");
    }

    @Test
    void blankSystemPrompt_streamPath_noSystemMessage() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(aiResponse("ok")));
        ChatClient client = buildClient("");

        client.prompt().user("就一句").stream().content().collectList().block();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.USER);
    }

    // ---------- T5：日志 Advisor 已挂载到默认链（AC-6） ----------

    @Test
    void requestLoggingAdvisor_isMounted_callPathEmitsBoundaryLog() {
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse("ok"));
        ChatClient client = buildClient("");

        client.prompt().user("你好").call().chatResponse();

        verify(chatModel, times(1)).call(any(Prompt.class));
        assertThat(logAppender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("模型调用开始[call]"))
                .anyMatch(e -> e.getFormattedMessage().contains("模型调用成功[call]")
                        && e.getFormattedMessage().contains("model=configured-model"));
    }

    @Test
    void requestLoggingAdvisor_isMounted_streamPathEmitsSummaryLog() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(aiResponse("你"), aiResponse("好")));
        ChatClient client = buildClient("");

        client.prompt().user("你好").stream().content().collectList().block();

        assertThat(logAppender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("模型调用开始[stream]"))
                .anyMatch(e -> e.getFormattedMessage().contains("模型流式调用成功[stream]")
                        && e.getFormattedMessage().contains("片段数=2"));
    }
}
