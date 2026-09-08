package com.dj.ai.agentchat.orchestration.planner;

import com.dj.ai.agentchat.orchestration.SddProperties;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.support.ModelInvoker;
import com.dj.ai.agentchat.orchestration.support.ObservationText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Planner 角色模型调用封装（迭代5，T3）：路由判定 / 再规划 / 汇总（Synth）。
 *
 * <p>纪律（M7 实证，红线）：
 * <ul>
 *   <li><b>每次模型调用都新建 {@code chatClient.prompt()} 规格</b>——同一 spec 重复终端调用会
 *       静默绕过 Advisor 链；路由重试/再规划/汇总均为独立 spec；</li>
 *   <li>Planner/Synth <b>永不挂载工具</b>（不调 {@code .tools()/.toolContext()}）；</li>
 *   <li>路由/再规划为同步 {@code .call()}，经 {@link ModelInvoker} 在 sdd-model-call daemon 池上
 *       以剩余预算为超时等待；Synth 为唯一流式调用（{@code .stream().content()}），
 *       <b>不套 Future 超时</b>（阻塞 get 会饿死 Reactor 订阅），由编排层做 deadline 预检；</li>
 *   <li>Synth Flux 经 {@code Flux.defer} 装配：每次（重）订阅重建 spec 与 Advisor 链；
 *       编排层只订阅一次、不做 Reactor 重试。</li>
 * </ul>
 *
 * <p>协议容错：路由/再规划输出经 {@link PlannerProtocol} 解析为 Unparseable 时，携带纠正指令
 * 重试 <b>1 次</b>（新鲜 spec）；仍不可解析：路由返回 {@link RouteDecision.Unparseable}
 * （编排层降级迭代4 直答），再规划返回 {@link ReplanDecision.Unparseable}（编排层强制收尾）。
 * 模型异常直接抛出（不做协议重试），由编排层决定降级/收尾；审计 best-effort 记录每行调用。
 */
@Slf4j
public class PlannerClient {

    private final ChatClient chatClient;
    private final SddProperties props;
    private final ModelInvoker modelInvoker;
    private final OrchestrationAuditService audit;

    public PlannerClient(ChatClient chatClient,
                         SddProperties props,
                         ModelInvoker modelInvoker,
                         OrchestrationAuditService audit) {
        this.chatClient = chatClient;
        this.props = props;
        this.modelInvoker = modelInvoker;
        this.audit = audit;
    }

    /**
     * 路由轮：判定 direct / plan。Unparseable 经纠正指令重试 1 次；仍失败返回
     * {@link RouteDecision.Unparseable}（调用方降级迭代4 直答）。模型异常原样抛出。
     *
     * @param timeoutNanos 本次同步调用超时（纳秒）；&lt;=0 不超时
     */
    public RouteDecision route(PlannerContext ctx, long timeoutNanos) {
        long start = System.nanoTime();
        String model = null;
        try {
            CallResult first = invokeSync(ctx, ctx.userText(), timeoutNanos);
            model = first.model();
            RouteDecision decision = PlannerProtocol.parseRoute(first.text(), props.getMaxTasks());
            if (decision instanceof RouteDecision.Unparseable) {
                long remaining = remainingNanos(timeoutNanos, System.nanoTime() - start);
                log.warn("Planner 路由输出无法解析，携带纠正指令重试 1 次: runId={}", ctx.runId());
                CallResult second = invokeSync(ctx,
                        ctx.userText() + "\n\n" + SddPrompts.ROUTE_RETRY_INSTRUCTION, remaining);
                if (StringUtils.hasText(second.model())) {
                    model = second.model();
                }
                decision = PlannerProtocol.parseRoute(second.text(), props.getMaxTasks());
            }
            long durationMs = elapsedMillis(start);
            if (decision instanceof RouteDecision.Unparseable) {
                audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                        OrchestrationAuditService.ROLE_PLANNER, null, null,
                        OrchestrationAuditService.STATUS_FAILED, durationMs, model,
                        "路由输出两次均无法解析为约定 JSON，降级普通对话直答");
            } else {
                audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                        OrchestrationAuditService.ROLE_PLANNER, null, null,
                        OrchestrationAuditService.STATUS_SUCCESS, durationMs, model, null);
            }
            return decision;
        } catch (RuntimeException e) {
            audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                    OrchestrationAuditService.ROLE_PLANNER, null, null,
                    OrchestrationAuditService.STATUS_FAILED, elapsedMillis(start), model,
                    "路由模型调用失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 再规划轮：基于累计观察决定 next/final。Unparseable 重试 1 次；仍失败返回
     * {@link ReplanDecision.Unparseable}（调用方强制收尾）。模型异常原样抛出。
     */
    public ReplanDecision replan(PlannerContext ctx, long timeoutNanos) {
        long start = System.nanoTime();
        String userText = SddPrompts.replanUserText(ctx.userText(), ctx.originalPlanText(),
                observationLines(ctx.observations()));
        String model = null;
        try {
            CallResult first = invokeSync(ctx, userText, timeoutNanos);
            model = first.model();
            ReplanDecision decision = PlannerProtocol.parseReplan(first.text());
            if (decision instanceof ReplanDecision.Unparseable) {
                long remaining = remainingNanos(timeoutNanos, System.nanoTime() - start);
                log.warn("Planner 再规划输出无法解析，携带纠正指令重试 1 次: runId={}, round={}",
                        ctx.runId(), ctx.round());
                CallResult second = invokeSync(ctx,
                        userText + "\n\n" + SddPrompts.ROUTE_RETRY_INSTRUCTION, remaining);
                if (StringUtils.hasText(second.model())) {
                    model = second.model();
                }
                decision = PlannerProtocol.parseReplan(second.text());
            }
            long durationMs = elapsedMillis(start);
            if (decision instanceof ReplanDecision.Unparseable) {
                audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                        OrchestrationAuditService.ROLE_PLANNER, null, null,
                        OrchestrationAuditService.STATUS_FAILED, durationMs, model,
                        "再规划输出两次均无法解析为约定 JSON，强制收尾汇总");
            } else {
                audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                        OrchestrationAuditService.ROLE_PLANNER, null, null,
                        OrchestrationAuditService.STATUS_SUCCESS, durationMs, model, null);
            }
            return decision;
        } catch (RuntimeException e) {
            audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                    OrchestrationAuditService.ROLE_PLANNER, null, null,
                    OrchestrationAuditService.STATUS_FAILED, elapsedMillis(start), model,
                    "再规划模型调用失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 汇总轮（Synth）：唯一流式调用。系统提示词 = Planner 提示词 + {@link SddPrompts#SYNTH_SUFFIX}
     * （后缀不可配置）；用户消息含累计观察，强制收尾时追加触顶指令。
     * 不套 Future 超时；complete/error 各审计一行 SYNTH。
     */
    public Flux<String> synthStream(PlannerContext ctx) {
        String system = resolveSystemPrompt(props.getPlanner(), SddPrompts.PLANNER_DEFAULT_SYSTEM)
                + SddPrompts.SYNTH_SUFFIX;
        String userText = SddPrompts.synthUserText(ctx.userText(),
                observationLines(ctx.observations()), ctx.forceFinishReason());
        List<Message> messages = assembleMessages(ctx.history(), userText);
        String auditModel = StringUtils.hasText(props.getPlanner().getModel())
                ? props.getPlanner().getModel() : null;
        return Flux.defer(() -> {
            long start = System.nanoTime();
            // 每次订阅新建 spec（M7 实证：同 Flux 重订阅会抛 No AroundAdvisor）
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(system)
                    .messages(messages);
            applyRoleOptions(spec, props.getPlanner());
            return spec.stream().content()
                    .doOnComplete(() -> audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                            OrchestrationAuditService.ROLE_SYNTH, null, null,
                            OrchestrationAuditService.STATUS_SUCCESS, elapsedMillis(start),
                            auditModel, null))
                    .doOnError(e -> audit.record(ctx.runId(), ctx.sessionId(), ctx.round(),
                            OrchestrationAuditService.ROLE_SYNTH, null, null,
                            OrchestrationAuditService.STATUS_FAILED, elapsedMillis(start),
                            auditModel, "汇总流式调用失败: " + e.getMessage()));
        });
    }

    // ---- 内部 ----

    private CallResult invokeSync(PlannerContext ctx, String userText, long timeoutNanos) {
        return modelInvoker.call(() -> {
            // 每次模型调用新建 spec（红线：复用 spec 会静默绕过 Advisor）
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(resolveSystemPrompt(props.getPlanner(), SddPrompts.PLANNER_DEFAULT_SYSTEM))
                    .messages(assembleMessages(ctx.history(), userText));
            applyRoleOptions(spec, props.getPlanner());
            ChatResponse response = spec.call().chatResponse();
            return new CallResult(extractText(response), resolveModel(response));
        }, timeoutNanos);
    }

    private record CallResult(String text, String model) {
    }

    private static List<Message> assembleMessages(List<Message> history, String userText) {
        List<Message> messages = new ArrayList<>(history == null ? List.of() : history);
        messages.add(new UserMessage(userText));
        return messages;
    }

    private static List<String> observationLines(List<ObservationText> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        return observations.stream().map(ObservationText::toLine).toList();
    }

    /** 角色系统提示词：配置空白 → 内置默认常量（编排角色必有提示词，AC-57）。 */
    public static String resolveSystemPrompt(SddProperties.Role role, String fallback) {
        String custom = role.getSystemPrompt();
        return StringUtils.hasText(custom) ? custom : fallback;
    }

    /** 仅在 model 非空白或 temperature 非 null 时设置请求级 options（否则请求形态=默认继承）。 */
    public static void applyRoleOptions(ChatClient.ChatClientRequestSpec spec, SddProperties.Role role) {
        String model = role.getModel();
        Double temperature = role.getTemperature();
        if (!StringUtils.hasText(model) && temperature == null) {
            return;
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (StringUtils.hasText(model)) {
            builder.model(model);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        spec.options(builder.build());
    }

    private static String extractText(ChatResponse response) {
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null) {
            return response.getResult().getOutput().getText();
        }
        return "";
    }

    /** 响应元数据 model 优先；空白时回退角色配置 model；再空白返回 null（审计可空）。 */
    private String resolveModel(ChatResponse response) {
        if (response != null && response.getMetadata() != null
                && StringUtils.hasText(response.getMetadata().getModel())) {
            return response.getMetadata().getModel();
        }
        String configured = props.getPlanner().getModel();
        return StringUtils.hasText(configured) ? configured : null;
    }

    private static long remainingNanos(long totalNanos, long elapsedNanos) {
        if (totalNanos <= 0) {
            return 0;
        }
        return Math.max(0L, totalNanos - elapsedNanos);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
