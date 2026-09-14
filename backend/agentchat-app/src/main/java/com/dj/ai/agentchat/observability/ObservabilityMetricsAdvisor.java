package com.dj.ai.agentchat.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.UsageUtils;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可观测性指标 Advisor（迭代9 FR-2 / FR-3）：模型调用耗时 + token 用量采集，
 * 同步/流式双路径（范式照 {@code RequestLoggingAdvisor}，副作用算子不改信号）。
 *
 * <p>ORDER=10：链按 order 升序嵌套，本 Advisor 在 RequestLoggingAdvisor(0) 内侧，
 * 既有日志语义/耗时口径不动。
 *
 * <p>落点：
 * <ul>
 *   <li>Timer {@code dj.chat.model.call.duration}{model,caller,path,outcome}；</li>
 *   <li>Counter {@code dj.chat.model.tokens.prompt|completion|total}{model,caller}；</li>
 *   <li>Counter {@code dj.chat.model.usage.missing}{model,caller,path}——usage 不可得
 *       （{@code usage == null || UsageUtils.isEmpty(usage)}）时显式降级计数（R-1 量化），
 *       token Counter 不增，<b>绝不做本地估算</b>；</li>
 *   <li>info 结构化日志（元信息、无正文，红线不破）：有值打
 *       {@code 模型调用token[call]: model=..., caller=..., prompt=..., completion=..., total=...}；
 *       降级打 {@code ...token=unavailable}。流式只在流末打一条。</li>
 * </ul>
 *
 * <p>caller 经请求级 advisor param {@value #PARAM_CALLER} 传递（M7 已核验
 * {@code AdvisedRequest.advisorParams()} / {@code AdvisorSpec.param}）：
 * planner/executor/synth 由编排 Clients 注入，普通路径缺省 {@code chat}。
 * 指标/日志逻辑全程 try/catch 自保（safeLog 同款），绝不影响调用链。
 * 本 Advisor 仅在总开关开启时经 {@code ChatClientConfig} 条件挂载（ObjectProvider），
 * 关闭态零接触。
 */
@Slf4j
public class ObservabilityMetricsAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    public static final String NAME = "observability-metrics-advisor";
    public static final int ORDER = 10;

    /** 请求级 advisor param 键：调用点（planner/executor/synth）；缺省 chat。 */
    public static final String PARAM_CALLER = "obs.caller";
    public static final String CALLER_CHAT = "chat";

    public static final String METER_CALL_DURATION = "dj.chat.model.call.duration";
    public static final String METER_TOKENS_PROMPT = "dj.chat.model.tokens.prompt";
    public static final String METER_TOKENS_COMPLETION = "dj.chat.model.tokens.completion";
    public static final String METER_TOKENS_TOTAL = "dj.chat.model.tokens.total";
    public static final String METER_USAGE_MISSING = "dj.chat.model.usage.missing";

    private static final String PATH_CALL = "call";
    private static final String PATH_STREAM = "stream";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_ERROR = "error";
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;
    private final String configuredModel;
    /**
     * 流式 token 实证子开关（R-1）：true 时 aroundStream 重写请求 options 带
     * {@code stream_options.include_usage}；默认 false。
     */
    private final boolean streamUsageEnabled;

    /**
     * 设计基准签名（streamUsage 关闭）；装配点用三参构造传入子开关值。
     */
    public ObservabilityMetricsAdvisor(MeterRegistry registry, String configuredModel) {
        this(registry, configuredModel, false);
    }

    public ObservabilityMetricsAdvisor(MeterRegistry registry, String configuredModel,
                                       boolean streamUsageEnabled) {
        this.registry = registry;
        this.configuredModel = configuredModel == null ? "" : configuredModel;
        this.streamUsageEnabled = streamUsageEnabled;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ---------------- 同步路径 ----------------

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        long start = System.nanoTime();
        String model = resolveModel(request);
        String caller = resolveCaller(request);
        try {
            AdvisedResponse response = chain.nextAroundCall(request);
            long elapsedMs = elapsedMillis(start);
            safeRun(() -> recordDuration(model, caller, PATH_CALL, OUTCOME_SUCCESS, elapsedMs));
            Usage usage = extractUsage(response);
            safeRun(() -> recordTokens(model, caller, PATH_CALL, usage));
            return response;
        } catch (Throwable t) {
            long elapsedMs = elapsedMillis(start);
            safeRun(() -> recordDuration(model, caller, PATH_CALL, OUTCOME_ERROR, elapsedMs));
            throw t;
        }
    }

    // ---------------- 流式路径 ----------------

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        // 流式 token 实证子开关（R-1）：重写请求 options 带 stream_options.include_usage。
        // 挂载点选址（设计清单 #2 实证结论）：M7 spec.options() 为整体替换（putfield 无 merge，
        // 会丢 model/temperature/reasoning-effort），而 Advisor 侧 request.chatOptions() 构造期
        // 已初始化为 defaultOptions.copy()——在公开 API AdvisedRequest.from().chatOptions() 上
        // 复制构造既合并安全，又一处覆盖普通流式与编排 synth 流式（替代设计中的两处 spec 挂载）。
        AdvisedRequest effectiveRequest = streamUsageEnabled ? withStreamUsage(request) : request;
        long start = System.nanoTime();
        String model = resolveModel(effectiveRequest);
        String caller = resolveCaller(effectiveRequest);
        // 逐片段滚动聚合 usage（M7 官方 UsageUtils.getCumulativeUsage 语义：末片段 usage 覆盖/累计）
        AtomicReference<Usage> acc = new AtomicReference<>();
        return chain.nextAroundStream(effectiveRequest)
                .doOnNext(response -> safeRun(() -> {
                    if (response != null && response.response() != null) {
                        acc.set(UsageUtils.getCumulativeUsage(acc.get(), response.response()));
                    }
                }))
                .doOnComplete(() -> {
                    long elapsedMs = elapsedMillis(start);
                    safeRun(() -> recordDuration(model, caller, PATH_STREAM, OUTCOME_SUCCESS, elapsedMs));
                    safeRun(() -> recordTokens(model, caller, PATH_STREAM, acc.get()));
                })
                .doOnError(t -> {
                    long elapsedMs = elapsedMillis(start);
                    safeRun(() -> recordDuration(model, caller, PATH_STREAM, OUTCOME_ERROR, elapsedMs));
                });
        // doOnXxx 为副作用算子，不改变 Flux 信号；流中断/错误原样下传。
    }

    // ---------------- 指标与日志 ----------------

    private void recordDuration(String model, String caller, String path, String outcome, long elapsedMs) {
        Timer.builder(METER_CALL_DURATION)
                .tag("model", model)
                .tag("caller", caller)
                .tag("path", path)
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * token 记录 + 日志：usage 可用 → 3 Counter + 数值日志；不可得 → missing Counter +1 +
     * {@code token=unavailable} 日志（FR-2.3 显式降级，token Counter 不增，绝不估算）。
     */
    private void recordTokens(String model, String caller, String path, Usage usage) {
        if (usage == null || UsageUtils.isEmpty(usage)) {
            Counter.builder(METER_USAGE_MISSING)
                    .tag("model", model)
                    .tag("caller", caller)
                    .tag("path", path)
                    .register(registry)
                    .increment();
            log.info("模型调用token[{}]: model={}, caller={}, token=unavailable", path, model, caller);
            return;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        if (prompt != null && prompt > 0) {
            Counter.builder(METER_TOKENS_PROMPT).tag("model", model).tag("caller", caller)
                    .register(registry).increment(prompt);
        }
        if (completion != null && completion > 0) {
            Counter.builder(METER_TOKENS_COMPLETION).tag("model", model).tag("caller", caller)
                    .register(registry).increment(completion);
        }
        if (total != null && total > 0) {
            Counter.builder(METER_TOKENS_TOTAL).tag("model", model).tag("caller", caller)
                    .register(registry).increment(total);
        }
        log.info("模型调用token[{}]: model={}, caller={}, prompt={}, completion={}, total={}",
                path, model, caller,
                prompt == null ? 0 : prompt,
                completion == null ? 0 : completion,
                total == null ? 0 : total);
    }

    // ---------------- 内部 ----------------

    /**
     * 流式 streamUsage 请求重写：在请求运行时 options（= 全局默认 options 的 copy，
     * model/temperature/reasoning-effort 完整）基础上复制构造并打开
     * {@code stream_options.include_usage}；异常时原样返回（降级不阻断）。
     */
    private static AdvisedRequest withStreamUsage(AdvisedRequest request) {
        try {
            ChatOptions options = request.chatOptions();
            OpenAiChatOptions newOptions = (options instanceof OpenAiChatOptions openAiOptions)
                    ? OpenAiChatOptions.fromOptions(openAiOptions)
                    : OpenAiChatOptions.builder().build();
            newOptions.setStreamUsage(true);
            return AdvisedRequest.from(request).chatOptions(newOptions).build();
        } catch (Exception e) {
            log.warn("streamUsage 请求重写失败，按原请求继续（不影响调用）: {}", e.getMessage());
            return request;
        }
    }

    private static Usage extractUsage(AdvisedResponse response) {
        try {
            if (response != null && response.response() != null
                    && response.response().getMetadata() != null) {
                return response.response().getMetadata().getUsage();
            }
        } catch (Exception ignored) {
            // 取 usage 失败按 unavailable 降级
        }
        return null;
    }

    /** caller 解析：请求级 advisor param；缺省/空白归一 chat。 */
    private static String resolveCaller(AdvisedRequest request) {
        try {
            Object value = request.advisorParams() == null ? null : request.advisorParams().get(PARAM_CALLER);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        } catch (Exception ignored) {
            // 取 caller 失败按缺省处理
        }
        return CALLER_CHAT;
    }

    /** model 解析（与 RequestLoggingAdvisor.resolveModel 同规则）；空白归一 unknown 防空 tag。 */
    private String resolveModel(AdvisedRequest request) {
        try {
            ChatOptions options = request.chatOptions();
            if (options != null && StringUtils.hasText(options.getModel())) {
                return options.getModel();
            }
        } catch (Exception ignored) {
            // 取模型标识失败回退配置值
        }
        return StringUtils.hasText(configuredModel) ? configuredModel : UNKNOWN;
    }

    /** 指标/日志自保：自身异常只打 warn，绝不抛出影响调用链。 */
    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("可观测性指标记录失败（不影响调用）: {}", e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
