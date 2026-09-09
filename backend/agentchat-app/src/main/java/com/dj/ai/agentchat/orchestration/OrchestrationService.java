package com.dj.ai.agentchat.orchestration;

import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.executor.ExecutorClient;
import com.dj.ai.agentchat.orchestration.executor.TaskOutcome;
import com.dj.ai.agentchat.orchestration.frame.PlanFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskView;
import com.dj.ai.agentchat.orchestration.planner.PlannerClient;
import com.dj.ai.agentchat.orchestration.planner.PlannerContext;
import com.dj.ai.agentchat.orchestration.planner.PlannerProtocol;
import com.dj.ai.agentchat.orchestration.planner.ReplanDecision;
import com.dj.ai.agentchat.orchestration.planner.RouteDecision;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;
import com.dj.ai.agentchat.orchestration.support.CapReasons;
import com.dj.ai.agentchat.orchestration.support.ObservationText;
import com.dj.ai.agentchat.tool.support.ToolMount;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

/**
 * SDD 子 Agent 编排状态机（迭代5，T4）：Planner 路由（direct/plan）→ Executor 顺序执行
 * → Planner 再规划（next/final）循环 → Synth 汇总流式收尾。
 *
 * <p>线程模型（NFR-3）：
 * <ul>
 *   <li>流式：{@code Flux.create(BUFFER).subscribeOn(sdd-orchestrator)}，整个循环
 *       （含 Planner/Executor 同步 .call()）跑在编排 daemon 池上，不占 Tomcat/Reactor 线程；</li>
 *   <li>同步：编排循环体 submit 到 sdd-orchestrator，Tomcat 线程以
 *       {@code get(总预算+5s 安全网)} 阻塞等待；</li>
 *   <li>模型 .call() 本身经 {@link com.dj.ai.agentchat.orchestration.support.ModelInvoker}
 *       在 sdd-model-call 池执行（Future 超时取消）。</li>
 * </ul>
 *
 * <p>触顶（六类，发起新模型调用前预检）：轮次 maxRounds（Planner 调用数）、子任务数
 * maxTasks（执行+跳过）、连续失败 maxConsecutiveFailures、总墙钟预算、重复规划异常 2 次、
 * 再规划不可解析——均强制 Synth 收尾；Synth 前剩余不足 {@link #SYNTH_MIN_BUDGET} 则
 * 按收尾失败抛 {@link ModelCallException}（SSE error/同步 502，AC-45）。
 *
 * <p>降级（AC-10）：路由模型异常或输出两次不可解析 → 调 degradeStream/degradeCall
 * 回到迭代4 普通路径（形态逐字节一致，不再发 plan/task 帧）。
 *
 * <p>编排层不注入 ConversationStore（AC-27）：历史以 {@code List<Message>} 传入，
 * 落库由 ChatService 对最终答案纯文本成对复用（AC-29/AC-55）。
 */
@Slf4j
public class OrchestrationService {

    /** direct 答案流式切片码元数（AC-53：24 codepoint/chunk，event:message 不变）。 */
    static final int DIRECT_CHUNK_CODEPOINTS = 24;
    /** Synth 发起前最小剩余预算；不足则收尾失败（AC-45）。 */
    static final long SYNTH_MIN_BUDGET_NANOS = Duration.ofSeconds(5).toNanos();
    private static final String SYNTH_FAILED_MESSAGE = "汇总阶段模型调用失败，请稍后重试";
    private static final String BUDGET_NO_SYNTH_MESSAGE = "编排已达预算/轮次上限且收尾时间不足，请稍后重试";

    private final PlannerClient plannerClient;
    private final ExecutorClient executorClient;
    private final OrchestrationAuditService audit;
    private final SddProperties props;
    private final ExecutorService orchestrator;

    public OrchestrationService(PlannerClient plannerClient,
                                ExecutorClient executorClient,
                                OrchestrationAuditService audit,
                                SddProperties props,
                                ExecutorService orchestrator) {
        this.plannerClient = plannerClient;
        this.executorClient = executorClient;
        this.audit = audit;
        this.props = props;
        this.orchestrator = orchestrator;
    }

    /**
     * 编排总墙钟预算（ChatService 装配 OrchInput 用）：流式 110s / 同步 55s，
     * 均短于容器超时（120s/60s，NFR-5）。
     */
    public Duration totalBudget(boolean streaming) {
        return streaming ? props.getTotalBudgetSse() : props.getTotalBudgetSync();
    }

    /**
     * 流式编排：返回最终答案分片 Flux（direct 切片 / synth 分片 / 降级流分片）；
     * plan/task 帧经 bridge 旁路下发。
     */
    public Flux<String> streamTurn(OrchInput in, OrchEventBridge bridge) {
        return Flux.<String>create(sink -> {
                    runTurn(in, bridge, sink);
                    // error 路径已 sink.error（isCancelled 为 true）；正常收尾在此 complete
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.fromExecutorService(orchestrator));
    }

    /**
     * 同步编排：阻塞直到拿到最终答案纯文本；总预算 +5s 安全网超时 → 502。
     */
    public OrchSyncOutcome syncTurn(OrchInput in) {
        Future<String> future = orchestrator.submit(
                () -> runTurn(in, new OrchEventBridge(), null));
        long waitNanos = in.totalBudget().toNanos() + Duration.ofSeconds(5).toNanos();
        try {
            String reply = future.get(waitNanos, TimeUnit.NANOSECONDS);
            return new OrchSyncOutcome(reply, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelCallException("编排总预算超时（同步安全网终止）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ModelCallException("SDD 编排执行失败: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ModelCallException("编排等待被中断", e);
        }
    }

    // ---- 状态机 ----

    /**
     * 跑完整轮编排；sink 非 null=流式（分片下发 + 取消检查），null=同步（聚合返回）。
     * 同步路径异常直接抛出（经 Future unwrap）；流式路径经 sink.error 下发。
     */
    private String runTurn(OrchInput in, OrchEventBridge bridge, FluxSink<String> sink) {
        OrchTurn turn = new OrchTurn(in, bridge, sink);
        try {
            turn.run();
        } catch (ModelCallException e) {
            if (sink != null) {
                sink.error(e);
                return null;
            }
            throw e;
        } catch (RuntimeException e) {
            ModelCallException wrapped = new ModelCallException("SDD 编排执行失败: " + e.getMessage(), e);
            if (sink != null) {
                sink.error(wrapped);
                return null;
            }
            throw wrapped;
        }
        return turn.reply.toString();
    }

    /** 单轮编排可变状态（每轮一个实例，线程封闭于 sdd-orchestrator 单任务）。 */
    private final class OrchTurn {

        private final OrchInput in;
        private final OrchEventBridge bridge;
        private final FluxSink<String> sink;
        private final StringBuilder reply = new StringBuilder();
        private final long deadlineNano;

        /** 计划台账：taskId → 视图（保序，含再规划新增/跳过）。 */
        private final LinkedHashMap<String, TaskView> ledger = new LinkedHashMap<>();
        private final Deque<TaskSpec> queue = new ArrayDeque<>();
        private final List<ObservationText> observations = new ArrayList<>();
        /** 再规划新增任务的 taskId 集合（重复规划检测只统计 replan 任务）。 */
        private final Set<String> replanTaskIds = new HashSet<>();

        private int roundsUsed = 1;
        private int tasksAccounted = 0;
        private int consecutiveFailures = 0;
        private int loopAnomalies = 0;
        private int taskSeq = 0;
        private String originalPlanText = "";
        private String lastReplanTitle = null;
        private boolean lastReplanFailed = false;

        OrchTurn(OrchInput in, OrchEventBridge bridge, FluxSink<String> sink) {
            this.in = in;
            this.bridge = bridge;
            this.sink = sink;
            this.deadlineNano = System.nanoTime() + in.totalBudget().toNanos();
        }

        void run() {
            PlannerContext routeCtx = PlannerContext.route(
                    in.runId(), in.sessionId(), in.userText(), in.history());
            RouteDecision decision;
            try {
                decision = plannerClient.route(routeCtx, remainingNanos());
            } catch (RuntimeException e) {
                log.warn("Planner 路由模型调用失败，降级迭代4 普通对话: runId={}, 原因={}",
                        in.runId(), e.getMessage());
                forwardDegrade();
                return;
            }
            if (decision instanceof RouteDecision.Unparseable) {
                log.warn("Planner 路由输出两次不可解析，降级迭代4 普通对话: runId={}", in.runId());
                forwardDegrade();
                return;
            }
            if (decision instanceof RouteDecision.Direct direct) {
                emitDirectAnswer(direct.answer());
                return;
            }
            registerInitialPlan((RouteDecision.Plan) decision);
            runLoop();
        }

        private void registerInitialPlan(RouteDecision.Plan plan) {
            List<TaskView> views = new ArrayList<>();
            for (TaskSpec t : plan.tasks()) {
                taskSeq++;
                TaskView view = new TaskView(t.taskId(), t.title(), TaskView.PENDING);
                ledger.put(t.taskId(), view);
                queue.add(t);
                views.add(view);
            }
            StringBuilder planText = new StringBuilder();
            for (TaskSpec t : plan.tasks()) {
                planText.append(t.taskId()).append(' ').append(t.title())
                        .append("：").append(t.goal()).append('\n');
            }
            originalPlanText = planText.toString();
            bridge.publish(new PlanFrame(1, views, plan.truncated() ? Boolean.TRUE : null));
        }

        private void runLoop() {
            while (true) {
                if (isCancelled()) {
                    return;
                }
                String cap = checkCaps();
                if (cap != null) {
                    forceSynth(cap);
                    return;
                }
                TaskSpec task = queue.poll();
                if (task == null) {
                    forceSynth(null);
                    return;
                }
                bridge.publish(TaskFrame.started(task.taskId(), task.title(), roundsUsed));
                ledger.put(task.taskId(), new TaskView(task.taskId(), task.title(), TaskView.RUNNING));

                TaskOutcome outcome = executorClient.execute(
                        task, in.toolMount(), executorCtx(), executorTimeoutNanos());
                tasksAccounted++;
                observations.add(ObservationText.executed(task, outcome));
                if (outcome.ok()) {
                    consecutiveFailures = 0;
                    ledger.put(task.taskId(),
                            new TaskView(task.taskId(), task.title(), TaskView.SUCCEEDED));
                    bridge.publish(TaskFrame.succeeded(task.taskId(), task.title(), outcome.durationMs()));
                } else {
                    consecutiveFailures++;
                    ledger.put(task.taskId(),
                            new TaskView(task.taskId(), task.title(), TaskView.FAILED));
                    bridge.publish(TaskFrame.failed(task.taskId(), task.title(), outcome.error()));
                    if (replanTaskIds.contains(task.taskId())) {
                        lastReplanFailed = true;
                    }
                }

                if (isCancelled()) {
                    return;
                }
                // 执行后立即再查触顶（连续失败/预算可能在本轮触发）
                cap = checkCaps();
                if (cap != null) {
                    forceSynth(cap);
                    return;
                }
                // 轮次硬顶：不再发起第 maxRounds+1 次 Planner 调用（AC-42）
                if (roundsUsed >= props.getMaxRounds()) {
                    forceSynth(CapReasons.ROUNDS);
                    return;
                }
                roundsUsed++;

                ReplanDecision replan;
                try {
                    replan = plannerClient.replan(replanCtx(), remainingNanos());
                } catch (RuntimeException e) {
                    log.warn("Planner 再规划模型调用失败，基于已有观察强制收尾: runId={}, round={}, 原因={}",
                            in.runId(), roundsUsed, e.getMessage());
                    forceSynth(null);
                    return;
                }
                if (replan instanceof ReplanDecision.Unparseable) {
                    forceSynth(CapReasons.REPLAN_PARSE);
                    return;
                }
                if (replan instanceof ReplanDecision.Final) {
                    forceSynth(null);
                    return;
                }
                ReplanDecision.Next next = (ReplanDecision.Next) replan;
                applySkipped(next.skippedTaskIds());
                TaskSpec nextTask = registerNextTask(next.task());
                // 重复规划检测（AC-46）：标题归一化相同 且 上一再规划任务终态 failed
                String normalizedTitle = normalizeTitle(nextTask.title());
                if (lastReplanFailed && normalizedTitle.equals(lastReplanTitle)) {
                    loopAnomalies++;
                    log.info("重复规划异常计数: runId={}, round={}, anomalies={}, title={}",
                            in.runId(), roundsUsed, loopAnomalies, nextTask.title());
                }
                lastReplanTitle = normalizedTitle;
                lastReplanFailed = false;
                queue.add(nextTask);
                bridge.publish(new PlanFrame(roundsUsed, ledgerViews(), null));
            }
        }

        private void applySkipped(List<String> skippedTaskIds) {
            if (skippedTaskIds == null) {
                return;
            }
            for (String sid : skippedTaskIds) {
                TaskView view = ledger.get(sid);
                if (view != null && !isTerminal(view.status())) {
                    ledger.put(sid, view.withStatus(TaskView.SKIPPED));
                    queue.removeIf(t -> t.taskId().equals(sid));
                    tasksAccounted++;
                    try {
                        audit.record(in.runId(), in.sessionId(), roundsUsed,
                                OrchestrationAuditService.ROLE_EXECUTOR, sid, view.title(),
                                OrchestrationAuditService.STATUS_SKIPPED, 0L, null, null);
                    } catch (RuntimeException e) {
                        // 审计 best-effort（AC-51），不影响编排
                        log.warn("SKIPPED 审计落库失败（忽略）: runId={}, taskId={}", in.runId(), sid);
                    }
                }
            }
        }

        private TaskSpec registerNextTask(TaskSpec raw) {
            taskSeq++;
            String id = PlannerProtocol.normalizeTaskId(
                    raw.taskId(), taskSeq, new HashSet<>(ledger.keySet()));
            TaskSpec task = new TaskSpec(id, raw.title(), raw.goal());
            ledger.put(id, new TaskView(id, raw.title(), TaskView.PENDING));
            replanTaskIds.add(id);
            return task;
        }

        /** 触顶预检（发起任何新模型调用前）；null=未触顶。 */
        private String checkCaps() {
            if (remainingNanos() <= 0) {
                return CapReasons.BUDGET;
            }
            if (tasksAccounted >= props.getMaxTasks()) {
                return CapReasons.TASKS;
            }
            if (consecutiveFailures >= props.getMaxConsecutiveFailures()) {
                return CapReasons.FAILURES;
            }
            if (loopAnomalies >= 2) {
                return CapReasons.LOOP;
            }
            return null;
        }

        /** 强制 Synth 收尾；剩余预算不足最小阈值 → 收尾失败（error/502，AC-45）。 */
        private void forceSynth(String capReason) {
            if (isCancelled()) {
                return;
            }
            long remaining = remainingNanos();
            if (remaining < SYNTH_MIN_BUDGET_NANOS) {
                log.warn("编排收尾时间不足，收尾失败: runId={}, capReason={}, remainingMs={}",
                        in.runId(), capReason, TimeUnit.NANOSECONDS.toMillis(Math.max(remaining, 0)));
                throw new ModelCallException(BUDGET_NO_SYNTH_MESSAGE, null);
            }
            log.info("编排强制收尾: runId={}, rounds={}, tasksAccounted={}, capReason={}",
                    in.runId(), roundsUsed, tasksAccounted, capReason);
            PlannerContext synthCtx = new PlannerContext(in.runId(), in.sessionId(), roundsUsed,
                    in.userText(), in.history(), originalPlanText, observations, capReason);
            forwardFlux(plannerClient.synthStream(synthCtx));
        }

        /** 路由降级：迭代4 普通路径（流式转发 / 同步聚合）。 */
        private void forwardDegrade() {
            if (sink != null) {
                forwardFlux(in.degradeStream().get());
            } else {
                emit(in.degradeCall().get());
            }
        }

        /**
         * 在编排线程上阻塞转发外部 Flux（synth/降级）：逐片 emit，取消即 dispose 停止；
         * error 归一为 ModelCallException（synth 失败语义，AC-50）。
         */
        private void forwardFlux(Flux<String> source) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            var disposable = source.subscribe(
                    chunk -> {
                        if (!isCancelled()) {
                            emit(chunk);
                        }
                    },
                    err -> {
                        errorRef.set(err);
                        done.countDown();
                    },
                    done::countDown);
            try {
                while (!done.await(50, TimeUnit.MILLISECONDS)) {
                    if (isCancelled()) {
                        disposable.dispose();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                disposable.dispose();
                Thread.currentThread().interrupt();
                throw new ModelCallException("编排等待汇总流被中断", e);
            }
            Throwable error = errorRef.get();
            if (error != null) {
                if (error instanceof ModelCallException mce) {
                    throw mce;
                }
                throw new ModelCallException(SYNTH_FAILED_MESSAGE, error);
            }
        }

        /** direct 答案：24 码元切片经 event:message 下发（无第二次模型调用，AC-7/AC-53）。 */
        private void emitDirectAnswer(String answer) {
            if (answer == null || answer.isEmpty()) {
                return;
            }
            int[] codePoints = answer.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i += DIRECT_CHUNK_CODEPOINTS) {
                if (isCancelled()) {
                    return;
                }
                int end = Math.min(i + DIRECT_CHUNK_CODEPOINTS, codePoints.length);
                emit(new String(codePoints, i, end - i));
            }
        }

        private void emit(String chunk) {
            reply.append(chunk);
            if (sink != null) {
                sink.next(chunk);
            }
        }

        private PlannerContext replanCtx() {
            return new PlannerContext(in.runId(), in.sessionId(), roundsUsed,
                    in.userText(), in.history(), originalPlanText, observations, null);
        }

        private PlannerContext executorCtx() {
            // Executor 用户消息仅含请求摘要+任务（ExecutorClient 不读 history，AC-28）
            return new PlannerContext(in.runId(), in.sessionId(), roundsUsed,
                    in.userText(), List.of(), "", observations, null);
        }

        /** 单任务超时：配置 task-timeout 与剩余预算取小；均无则剩余预算兜底（绝不越总预算）。 */
        private long executorTimeoutNanos() {
            long remaining = remainingNanos();
            if (props.getTaskTimeout() != null) {
                return Math.min(props.getTaskTimeout().toNanos(), remaining);
            }
            return remaining;
        }

        private long remainingNanos() {
            return deadlineNano - System.nanoTime();
        }

        private boolean isCancelled() {
            return sink != null && sink.isCancelled();
        }

        private List<TaskView> ledgerViews() {
            return new ArrayList<>(ledger.values());
        }

        private static boolean isTerminal(String status) {
            return TaskView.SUCCEEDED.equals(status) || TaskView.FAILED.equals(status)
                    || TaskView.SKIPPED.equals(status);
        }

        /** 标题归一化：去空白与标点、小写（重复规划检测，AC-46）。 */
        private static String normalizeTitle(String title) {
            if (title == null) {
                return "";
            }
            return title.toLowerCase()
                    .replaceAll("[\\s\\p{Punct}…—·、。，；：？！（）【】「」『』《》‘’“”]+", "");
        }
    }
}
