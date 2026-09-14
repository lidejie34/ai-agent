package com.dj.ai.agentchat.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 迭代9 FR-2/FR-3：{@link ObservabilityMetricsAdvisor}——同步/流式双路径
 * Timer + token Counter 记录、usage 缺失显式降级（unavailable + missing Counter，
 * token Counter 不增）、异常路径 outcome=error、caller tag 缺省 chat、
 * streamUsage 实证子开关的请求重写（合并安全：保留 model/temperature）。
 */
class ObservabilityMetricsAdvisorTest {

    private MeterRegistry registry;
    private ObservabilityMetricsAdvisor advisor;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger advisorLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        advisor = new ObservabilityMetricsAdvisor(registry, "configured-model");
        advisorLogger = (Logger) LoggerFactory.getLogger(ObservabilityMetricsAdvisor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        advisorLogger.addAppender(logAppender);
        advisorLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        advisorLogger.detachAppender(logAppender);
    }

    // ---------- 夹具 ----------

    private static AdvisedRequest request(Map<String, Object> advisorParams) {
        return AdvisedRequest.builder()
                .chatModel(mock(ChatModel.class))
                .userText("你好")
                .chatOptions(OpenAiChatOptions.builder().model("m-x").temperature(0.7).build())
                .advisorParams(advisorParams)
                .build();
    }

    private static ChatResponse chatResponseWithUsage(org.springframework.ai.chat.metadata.Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }

    private static AdvisedResponse advisedResponseWithUsage(org.springframework.ai.chat.metadata.Usage usage) {
        return new AdvisedResponse(chatResponseWithUsage(usage), Map.of());
    }

    private double counterCount(String meter, String... tags) {
        try {
            return registry.get(meter).tags(tags).counter().count();
        } catch (Exception e) {
            return 0;
        }
    }

    private long timerCount(String outcome, String path) {
        try {
            return registry.get(ObservabilityMetricsAdvisor.METER_CALL_DURATION)
                    .tags("model", "m-x", "caller", "chat", "path", path, "outcome", outcome)
                    .timer().count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 同步路径 ----------

    @Test
    void call_success_recordsTimerAndTokenCounters_andValueLog() {
        CallAroundAdvisorChain chain = req -> advisedResponseWithUsage(new DefaultUsage(812, 156, 968));

        AdvisedResponse response = advisor.aroundCall(request(Map.of()), chain);

        assertThat(response).isNotNull();
        assertThat(timerCount("success", "call")).isEqualTo(1);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_PROMPT,
                "model", "m-x", "caller", "chat")).isEqualTo(812);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_COMPLETION,
                "model", "m-x", "caller", "chat")).isEqualTo(156);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "chat")).isEqualTo(968);
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage()
                .contains("模型调用token[call]") && e.getFormattedMessage().contains("prompt=812")
                && e.getFormattedMessage().contains("completion=156")
                && e.getFormattedMessage().contains("total=968"));
    }

    @Test
    void call_emptyUsage_degradesToUnavailable_missingCounterOnly() {
        CallAroundAdvisorChain chain = req -> advisedResponseWithUsage(new EmptyUsage());

        advisor.aroundCall(request(Map.of()), chain);

        assertThat(timerCount("success", "call")).isEqualTo(1);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_USAGE_MISSING,
                "model", "m-x", "caller", "chat", "path", "call")).isEqualTo(1);
        // token Counter 不增（绝不估算）
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_PROMPT,
                "model", "m-x", "caller", "chat")).isZero();
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "chat")).isZero();
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage()
                .contains("模型调用token[call]") && e.getFormattedMessage().contains("token=unavailable"));
    }

    @Test
    void call_nullUsage_degradesToUnavailable() {
        ChatResponse noUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().build());
        CallAroundAdvisorChain chain = req -> new AdvisedResponse(noUsage, Map.of());

        advisor.aroundCall(request(Map.of()), chain);

        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_USAGE_MISSING,
                "model", "m-x", "caller", "chat", "path", "call")).isEqualTo(1);
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains("token=unavailable"));
    }

    @Test
    void call_error_recordsErrorTimer_andRethrows() {
        RuntimeException boom = new RuntimeException("model down");
        CallAroundAdvisorChain chain = req -> {
            throw boom;
        };

        assertThatThrownBy(() -> advisor.aroundCall(request(Map.of()), chain)).isSameAs(boom);
        assertThat(timerCount("error", "call")).isEqualTo(1);
        assertThat(timerCount("success", "call")).isZero();
    }

    @Test
    void callerParam_tagsPlannerInsteadOfDefaultChat() {
        CallAroundAdvisorChain chain = req -> advisedResponseWithUsage(new DefaultUsage(10, 5, 15));

        advisor.aroundCall(request(Map.of(ObservabilityMetricsAdvisor.PARAM_CALLER, "planner")), chain);

        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "planner")).isEqualTo(15);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "chat")).isZero();
    }

    // ---------- 流式路径 ----------

    @Test
    void stream_lastChunkUsage_aggregatesAndRecordsOnce() {
        AdvisedResponse chunk1 = new AdvisedResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你"))),
                        ChatResponseMetadata.builder().build()), Map.of());
        AdvisedResponse chunk2 = advisedResponseWithUsage(new DefaultUsage(100, 20, 120));
        StreamAroundAdvisorChain chain = req -> Flux.just(chunk1, chunk2);

        List<AdvisedResponse> collected = advisor.aroundStream(request(Map.of()), chain)
                .collectList().block();

        assertThat(collected).hasSize(2);
        assertThat(timerCount("success", "stream")).isEqualTo(1);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "chat")).isEqualTo(120);
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage()
                .contains("模型调用token[stream]") && e.getFormattedMessage().contains("total=120"));
    }

    @Test
    void stream_noUsageAnywhere_degradesToUnavailable() {
        AdvisedResponse chunk = new AdvisedResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你"))),
                        ChatResponseMetadata.builder().build()), Map.of());
        StreamAroundAdvisorChain chain = req -> Flux.just(chunk, chunk);

        advisor.aroundStream(request(Map.of()), chain).collectList().block();

        assertThat(timerCount("success", "stream")).isEqualTo(1);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_USAGE_MISSING,
                "model", "m-x", "caller", "chat", "path", "stream")).isEqualTo(1);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "m-x", "caller", "chat")).isZero();
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage()
                .contains("模型调用token[stream]") && e.getFormattedMessage().contains("token=unavailable"));
    }

    @Test
    void stream_error_recordsErrorTimer_andPropagates() {
        StreamAroundAdvisorChain chain = req -> Flux.error(new RuntimeException("net io"));

        assertThatThrownBy(() -> advisor.aroundStream(request(Map.of()), chain).collectList().block())
                .hasMessageContaining("net io");
        assertThat(timerCount("error", "stream")).isEqualTo(1);
    }

    // ---------- streamUsage 实证子开关（R-1，清单 #2 合并安全） ----------

    @Test
    void streamUsageEnabled_rewritesOptions_preservingModelAndTemperature() {
        ObservabilityMetricsAdvisor streamUsageAdvisor =
                new ObservabilityMetricsAdvisor(registry, "configured-model", true);
        AtomicReference<AdvisedRequest> seen = new AtomicReference<>();
        StreamAroundAdvisorChain chain = req -> {
            seen.set(req);
            return Flux.just(advisedResponseWithUsage(new DefaultUsage(1, 1, 2)));
        };

        streamUsageAdvisor.aroundStream(request(Map.of()), chain).collectList().block();

        assertThat(seen.get().chatOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) seen.get().chatOptions();
        assertThat(options.getStreamUsage()).isTrue();
        // 合并安全：默认 options 的 model/temperature 不丢
        assertThat(options.getModel()).isEqualTo("m-x");
        assertThat(options.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void streamUsageDisabled_passesRequestThroughUnchanged() {
        AtomicReference<AdvisedRequest> seen = new AtomicReference<>();
        StreamAroundAdvisorChain chain = req -> {
            seen.set(req);
            return Flux.just(advisedResponseWithUsage(new DefaultUsage(1, 1, 2)));
        };
        AdvisedRequest original = request(Map.of());

        advisor.aroundStream(original, chain).collectList().block();

        assertThat(seen.get()).isSameAs(original);
    }

    @Test
    void blankModel_fallsBackToConfiguredModel_thenUnknown() {
        AdvisedRequest blankModelRequest = AdvisedRequest.builder()
                .chatModel(mock(ChatModel.class))
                .userText("你好")
                .chatOptions(OpenAiChatOptions.builder().build())
                .advisorParams(Map.of())
                .build();
        CallAroundAdvisorChain chain = req -> advisedResponseWithUsage(new DefaultUsage(1, 1, 2));

        advisor.aroundCall(blankModelRequest, chain);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "configured-model", "caller", "chat")).isEqualTo(2);

        ObservabilityMetricsAdvisor noModelAdvisor = new ObservabilityMetricsAdvisor(registry, "");
        noModelAdvisor.aroundCall(blankModelRequest, chain);
        assertThat(counterCount(ObservabilityMetricsAdvisor.METER_TOKENS_TOTAL,
                "model", "unknown", "caller", "chat")).isEqualTo(2);
    }

    @Test
    void nameAndOrder_areStable() {
        assertThat(advisor.getName()).isEqualTo("observability-metrics-advisor");
        assertThat(advisor.getOrder()).isEqualTo(10);
    }
}
