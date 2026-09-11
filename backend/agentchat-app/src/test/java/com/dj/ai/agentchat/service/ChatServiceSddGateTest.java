package com.dj.ai.agentchat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.orchestration.OrchestrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T0：ChatService 编排开关收口（AC-1/AC-3/AC-5/AC-6）——
 * 编排 bean 缺席（开关关闭）：sdd 三态均不走编排，sdd:true 记 warn 忽略；
 * 编排 bean 在场（开关开启）：sdd=null/true 走编排，sdd:false 强制普通路径。
 */
class ChatServiceSddGateTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(ChatService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    private ChatService serviceWith(OrchestrationService orchestration) {
        ChatClient chatClient = mock(ChatClient.class);
        return new ChatService(chatClient, "ark-test-key", "model",
                3, java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(100),
                null, 20, true, null,
                new ChatService.FixedObjectProvider<>(orchestration),
                null);
    }

    @Test
    void absentOrchestration_neverEnables_gateReturnsFalse() {
        ChatService service = serviceWith(null);

        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, null))).isFalse();
        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.FALSE))).isFalse();
        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.TRUE))).isFalse();
    }

    @Test
    void absentOrchestration_sddTrue_logsWarnAndIgnored() {
        ChatService service = serviceWith(null);

        service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.TRUE));

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("sdd");
        });
    }

    @Test
    void absentOrchestration_sddFalseOrNull_doesNotWarn() {
        ChatService service = serviceWith(null);

        service.orchestrationEnabled(new ChatRequest("你好", null, null, null));
        service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.FALSE));

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void presentOrchestration_enablesForNullAndTrue_bypassesForFalse() {
        ChatService service = serviceWith(mock(OrchestrationService.class));

        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, null))).isTrue();
        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.TRUE))).isTrue();
        assertThat(service.orchestrationEnabled(new ChatRequest("你好", null, null, Boolean.FALSE))).isFalse();
    }
}
