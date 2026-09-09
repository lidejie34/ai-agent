package com.dj.ai.agentchat.orchestration;

import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.orchestration.audit.OrchestrationAuditService;
import com.dj.ai.agentchat.orchestration.executor.ExecutorClient;
import com.dj.ai.agentchat.orchestration.executor.TaskOutcome;
import com.dj.ai.agentchat.orchestration.frame.OrchFrame;
import com.dj.ai.agentchat.orchestration.frame.PlanFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskFrame;
import com.dj.ai.agentchat.orchestration.frame.TaskView;
import com.dj.ai.agentchat.orchestration.planner.PlannerClient;
import com.dj.ai.agentchat.orchestration.planner.PlannerContext;
import com.dj.ai.agentchat.orchestration.planner.ReplanDecision;
import com.dj.ai.agentchat.orchestration.planner.RouteDecision;
import com.dj.ai.agentchat.orchestration.planner.TaskSpec;
import com.dj.ai.agentchat.orchestration.support.CapReasons;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import com.dj.ai.agentchat.tool.support.ToolMount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrchestrationService 状态机测试（迭代5，T4，§10.1 矩阵 22 例）：
 * mock PlannerClient/ExecutorClient/AuditService，单线程 orchestrator 池保证确定性；
 * 帧经 OrchEventBridge 收集断言顺序与内容。
 */
class OrchestrationServiceTest {

    private PlannerClient planner;
    private ExecutorClient executor;
    private OrchestrationAuditService audit;
    private SddProperties props;
    private ExecutorService pool;
    private OrchestrationService service;
    private OrchEventBridge bridge;
    private List<OrchFrame> frames;

    @BeforeEach
    void setUp() {
        planner = mock(PlannerClient.class);
        executor = mock(ExecutorClient.class);
        audit = mock(OrchestrationAuditService.class);
        props = new SddProperties();
        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orch-test");
            t.setDaemon(true);
            return t;
        });
        service = new OrchestrationService(planner, executor, audit, props, pool);
        bridge = new OrchEventBridge();
        frames = Collections.synchronizedList(new ArrayList<>());
        bridge.setSink(frames::add);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ---- 夹具 ----

    private static TaskSpec task(String id, String title) {
        return new TaskSpec(id, title, "goal-" + id);
    }

    private static RouteDecision.Plan plan(TaskSpec... tasks) {
        return new RouteDecision.Plan(List.of(tasks), false);
    }

    private static TaskOutcome ok(String text) {
        return TaskOutcome.success(text, 42, "model-x");
    }

    private static TaskOutcome fail(String error) {
        return TaskOutcome.failure(error, 30, "model-x", false);
    }

    private OrchInput streamInput(Duration budget) {
        return new OrchInput("run-1", "帮我分析日志", List.of(), null, null,
                budget, true,
                () -> Flux.just("降级", "片段"),
                () -> "降级回复");
    }

    private OrchInput streamInput() {
        return streamInput(Duration.ofSeconds(110));
    }

    private OrchInput streamInputNoDegrade() {
        return new OrchInput("run-1", "帮我分析日志", List.of(), null, null,
                Duration.ofSeconds(110), true,
                () -> { throw new AssertionError("降级流不应被调用"); },
                () -> { throw new AssertionError("降级同步不应被调用"); });
    }

    private OrchInput syncInput() {
        return new OrchInput("run-1", "帮我分析日志", List.of(), null, null,
                Duration.ofSeconds(55), false,
                () -> { throw new AssertionError("降级流不应被调用"); },
                () -> "降级回复");
    }

    private List<String> collect(Flux<String> flux) {
        return flux.collectList().block(Duration.ofSeconds(15));
    }

    private void stubSynth(String... chunks) {
        when(planner.synthStream(any())).thenReturn(Flux.just(chunks));
    }

    private PlanFrame firstPlanFrame() {
        return (PlanFrame) frames.stream().filter(f -> f instanceof PlanFrame).findFirst().orElseThrow();
    }

    // ---- 1. direct：仅 1 次模型调用、无过程帧、24 码元切片 ----

    @Test
    void direct_oneModelCall_noFrames_chunkedBy24CodePoints() {
        String answer = "甲".repeat(50);
        when(planner.route(any(), anyLong())).thenReturn(new RouteDecision.Direct(answer));

        List<String> chunks = collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(String.join("", chunks)).isEqualTo(answer);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(24);
        assertThat(chunks.get(2)).hasSize(2);
        assertThat(frames).isEmpty();
        verify(executor, never()).execute(any(), any(), any(), anyLong());
        verify(planner, never()).replan(any(), anyLong());
        verify(planner, never()).synthStream(any());
    }

    // ---- 2/4. plan 全帧序：plan → started → succeeded →（final）→ synth ----

    @Test
    void plan_happyPath_frameOrderAndContent() {
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "查日志"), task("t2", "写结论")));
        when(executor.execute(any(), any(), any(), anyLong()))
                .thenReturn(ok("结果1"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        stubSynth("最终", "答案");

        List<String> chunks = collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("最终答案");
        // plan(round1) + task started + task succeeded（replan final 不再发 plan 帧）
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0)).isInstanceOf(PlanFrame.class);
        PlanFrame pf = (PlanFrame) frames.get(0);
        assertThat(pf.round()).isEqualTo(1);
        assertThat(pf.truncated()).isNull();
        assertThat(pf.tasks()).extracting(TaskView::taskId).containsExactly("t1", "t2");
        assertThat(pf.tasks()).extracting(TaskView::status).containsOnly(TaskView.PENDING);
        assertThat(frames.get(1)).isInstanceOf(TaskFrame.class);
        TaskFrame started = (TaskFrame) frames.get(1);
        assertThat(started.status()).isEqualTo(TaskFrame.STARTED);
        assertThat(started.taskId()).isEqualTo("t1");
        assertThat(started.round()).isEqualTo(1);
        assertThat(frames.get(2)).isInstanceOf(TaskFrame.class);
        TaskFrame succeeded = (TaskFrame) frames.get(2);
        assertThat(succeeded.status()).isEqualTo(TaskFrame.SUCCEEDED);
        assertThat(succeeded.taskId()).isEqualTo("t1");
        assertThat(succeeded.durationMs()).isEqualTo(42L);
        // replan 立刻 final：t2 不再执行
        verify(executor, times(1)).execute(any(), any(), any(), anyLong());
    }

    // ---- 3. 严格顺序：同一时刻仅一个 Executor 在途 ----

    @Test
    void executor_callsAreSequential_noParallel() {
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二"), task("t3", "三")));
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        when(executor.execute(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            Thread.sleep(80);
            concurrent.decrementAndGet();
            return ok("ok");
        });
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t4", "四"), List.of()))
                .thenReturn(new ReplanDecision.Next(task("t5", "五"), List.of()))
                .thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(maxConcurrent.get()).isEqualTo(1);
        verify(executor, times(3)).execute(any(), any(), any(), anyLong());
    }

    // ---- 5. replan next：新任务 started + plan 帧 round 递增携带最新台账 ----

    @Test
    void replanNext_publishesPlanFrameWithIncrementedRound() {
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "查日志")));
        when(executor.execute(any(), any(), any(), anyLong()))
                .thenReturn(ok("结果1"))
                .thenReturn(ok("结果2"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t2", "补充查询"), List.of()))
                .thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        List<PlanFrame> planFrames = frames.stream()
                .filter(f -> f instanceof PlanFrame).map(f -> (PlanFrame) f).toList();
        assertThat(planFrames).hasSize(2);
        assertThat(planFrames.get(1).round()).isEqualTo(2);
        assertThat(planFrames.get(1).tasks()).extracting(TaskView::taskId).containsExactly("t1", "t2");
        assertThat(planFrames.get(1).tasks()).extracting(TaskView::status)
                .containsExactly(TaskView.SUCCEEDED, TaskView.PENDING);
        List<TaskFrame> taskFrames = frames.stream()
                .filter(f -> f instanceof TaskFrame).map(f -> (TaskFrame) f).toList();
        assertThat(taskFrames).extracting(TaskFrame::taskId).contains("t2");
        assertThat(taskFrames).anySatisfy(f -> {
            if (f.taskId().equals("t2") && f.status().equals(TaskFrame.STARTED)) {
                assertThat(f.round()).isEqualTo(2);
            }
        });
        verify(executor, times(2)).execute(any(), any(), any(), anyLong());
    }

    // ---- 6. skipped：台账 skipped + 审计 SKIPPED + 不执行 + 不计失败 ----

    @Test
    void replanSkipped_markedLedger_audited_removedFromQueue() {
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二"), task("t3", "三")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t4", "新任务"), List.of("t2", "t3")))
                .thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        // 只执行 t1 与 t4；t2/t3 跳过
        verify(executor, times(2)).execute(any(), any(), any(), anyLong());
        PlanFrame latest = frames.stream()
                .filter(f -> f instanceof PlanFrame).map(f -> (PlanFrame) f)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(latest.tasks()).extracting(TaskView::status)
                .containsExactly(TaskView.SUCCEEDED, TaskView.SKIPPED, TaskView.SKIPPED, TaskView.PENDING);
        verify(audit, times(2)).record(anyString(), any(), anyInt(),
                eq(OrchestrationAuditService.ROLE_EXECUTOR), anyString(), anyString(),
                eq(OrchestrationAuditService.STATUS_SKIPPED), anyLong(), any(), any());
    }

    // ---- 7. 任务失败结构化：failed 帧 + error + 观察回灌 + 编排不中断 ----

    @Test
    void taskFailure_failedFrameAndObservation_continuesToSynth() {
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "查日志"), task("t2", "备用")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(fail("DB 连接超时"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        stubSynth("最终答案");

        List<String> chunks = collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("最终答案");
        TaskFrame failedFrame = frames.stream()
                .filter(f -> f instanceof TaskFrame).map(f -> (TaskFrame) f)
                .filter(f -> f.status().equals(TaskFrame.FAILED)).findFirst().orElseThrow();
        assertThat(failedFrame.taskId()).isEqualTo("t1");
        assertThat(failedFrame.error()).isEqualTo("DB 连接超时");
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).replan(ctxCap.capture(), anyLong());
        assertThat(ctxCap.getValue().observations()).hasSize(1);
        assertThat(ctxCap.getValue().observations().get(0).status()).isEqualTo(TaskView.FAILED);
        assertThat(ctxCap.getValue().observations().get(0).summary()).contains("DB 连接超时");
    }

    // ---- 8. 触顶-轮次：达 maxRounds 不再调 Planner，强制收尾 ----

    @Test
    void capRounds_noMorePlannerCalls_forceSynthWithReason() {
        props.setMaxRounds(2);
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "一")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t2", "二"), List.of()));
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        verify(planner, times(1)).replan(any(), anyLong());
        verify(executor, times(2)).execute(any(), any(), any(), anyLong());
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).synthStream(ctxCap.capture());
        assertThat(ctxCap.getValue().forceFinishReason()).isEqualTo(CapReasons.ROUNDS);
    }

    // ---- 9. 触顶-任务数：累计执行+跳过达 maxTasks 强制收尾 ----

    @Test
    void capTasks_forceSynthWithReason() {
        props.setMaxTasks(2);
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t3", "三"), List.of()));
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        verify(executor, times(2)).execute(any(), any(), any(), anyLong());
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).synthStream(ctxCap.capture());
        assertThat(ctxCap.getValue().forceFinishReason()).isEqualTo(CapReasons.TASKS);
    }

    // ---- 10. 触顶-连续失败：达上限强制收尾；成功重置计数 ----

    @Test
    void capConsecutiveFailures_successResetsCounter() {
        props.setMaxConsecutiveFailures(2);
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二"), task("t3", "三"), task("t4", "四")));
        when(executor.execute(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            TaskSpec spec = inv.getArgument(0);
            return switch (spec.taskId()) {
                case "t1", "t3", "t4" -> fail("失败-" + spec.taskId());
                default -> ok("成功-" + spec.taskId());
            };
        });
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("a1", "补一"), List.of()))
                .thenReturn(new ReplanDecision.Next(task("a2", "补二"), List.of()))
                .thenReturn(new ReplanDecision.Next(task("a3", "补三"), List.of()));
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        // t1 失败(cf=1) → t2 成功(重置) → t3 失败(cf=1) → t4 失败(cf=2) 触顶
        verify(executor, times(4)).execute(any(), any(), any(), anyLong());
        verify(planner, times(3)).replan(any(), anyLong());
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).synthStream(ctxCap.capture());
        assertThat(ctxCap.getValue().forceFinishReason()).isEqualTo(CapReasons.FAILURES);
    }

    // ---- 11. 触顶-总预算：剩余时间不足收尾 → error 信号，不发起 synth ----

    @Test
    void capBudget_remainingTooShortForSynth_errorSignal() {
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二")));
        when(executor.execute(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            Thread.sleep(200);
            return ok("ok");
        });
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());

        assertThatThrownBy(() -> collect(service.streamTurn(streamInput(Duration.ofMillis(300)), bridge)))
                .isInstanceOf(ModelCallException.class)
                .hasMessageContaining("预算");

        verify(planner, never()).synthStream(any());
    }

    // ---- 12. 触顶-重复规划：同标题归一化 + 前任务 failed，累计 2 次强制收尾 ----

    @Test
    void capLoop_sameFailedTitleTwice_forceSynth() {
        // 连续失败上限放宽，让重复规划检测先生效
        props.setMaxConsecutiveFailures(4);
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "初始任务")));
        when(executor.execute(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            TaskSpec spec = inv.getArgument(0);
            return "t1".equals(spec.taskId()) ? ok("ok") : fail("又失败了");
        });
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t2", "重试查询"), List.of()))
                .thenReturn(new ReplanDecision.Next(task("t3", "重试查询"), List.of()))
                .thenReturn(new ReplanDecision.Next(task("t4", "重试查询"), List.of()));
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        // t1 成功；t2 失败→replan 同标题(异常1)；t3 失败→replan 同标题(异常2)；t4 不执行
        verify(executor, times(3)).execute(any(), any(), any(), anyLong());
        verify(planner, times(3)).replan(any(), anyLong());
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).synthStream(ctxCap.capture());
        assertThat(ctxCap.getValue().forceFinishReason()).isEqualTo(CapReasons.LOOP);
    }

    // ---- 13. 任务超时：TIMEOUT 观察（status=timeout）+ failed 帧 ----

    @Test
    void taskTimeout_timeoutObservationAndFailedFrame() {
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "慢任务")));
        when(executor.execute(any(), any(), any(), anyLong()))
                .thenReturn(TaskOutcome.failure("子任务执行超时（已达单任务时限）", 5000, null, true));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        collect(service.streamTurn(streamInputNoDegrade(), bridge));

        TaskFrame failedFrame = frames.stream()
                .filter(f -> f instanceof TaskFrame).map(f -> (TaskFrame) f)
                .filter(f -> f.status().equals(TaskFrame.FAILED)).findFirst().orElseThrow();
        assertThat(failedFrame.error()).contains("超时");
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).replan(ctxCap.capture(), anyLong());
        assertThat(ctxCap.getValue().observations().get(0).status())
                .isEqualTo(com.dj.ai.agentchat.orchestration.support.ObservationText.STATUS_TIMEOUT);
    }

    // ---- 15. 路由不可解析 → 降级（流/同步）；无过程帧、无 Executor ----

    @Test
    void routeUnparseable_streamDegrades_noFrames() {
        when(planner.route(any(), anyLong())).thenReturn(new RouteDecision.Unparseable());

        List<String> chunks = collect(service.streamTurn(streamInput(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("降级片段");
        assertThat(frames).isEmpty();
        verify(executor, never()).execute(any(), any(), any(), anyLong());
        verify(planner, never()).synthStream(any());
    }

    @Test
    void routeUnparseable_syncDegrades() {
        when(planner.route(any(), anyLong())).thenReturn(new RouteDecision.Unparseable());

        OrchSyncOutcome outcome = service.syncTurn(syncInput());

        assertThat(outcome.reply()).isEqualTo("降级回复");
        verify(executor, never()).execute(any(), any(), any(), anyLong());
    }

    @Test
    void routeModelException_degrades() {
        when(planner.route(any(), anyLong()))
                .thenThrow(new ModelCallException("route boom", new RuntimeException("up")));

        List<String> chunks = collect(service.streamTurn(streamInput(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("降级片段");
        assertThat(frames).isEmpty();
    }

    // ---- 17. replan 不可解析 → 强制收尾（不降级），原因 REPLAN_PARSE ----

    @Test
    void replanUnparseable_forcesSynth_notDegrade() {
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "一")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Unparseable());
        stubSynth("汇总");

        List<String> chunks = collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("汇总");
        ArgumentCaptor<PlannerContext> ctxCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).synthStream(ctxCap.capture());
        assertThat(ctxCap.getValue().forceFinishReason()).isEqualTo(CapReasons.REPLAN_PARSE);
    }

    // ---- 18. Synth 失败 → error 信号 ----

    @Test
    void synthFailure_errorSignal() {
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "一")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        when(planner.synthStream(any()))
                .thenReturn(Flux.error(new ModelCallException("synth-boom", new RuntimeException("up"))));

        assertThatThrownBy(() -> collect(service.streamTurn(streamInputNoDegrade(), bridge)))
                .isInstanceOf(ModelCallException.class)
                .hasMessageContaining("synth-boom");
    }

    // ---- 19. 工具 mount 整轮共享：Executor 多次收到同一实例 ----

    @Test
    void toolMount_sharedAcrossAllExecutorCalls() {
        ToolMount mount = new ToolMount(List.of(mock(ToolCallback.class)),
                Map.of("requestId", "run-1"), mock(ToolCallBridge.class));
        OrchInput in = new OrchInput("run-1", "帮我分析日志", List.of(), null, mount,
                Duration.ofSeconds(110), true,
                () -> { throw new AssertionError("不应降级"); },
                () -> { throw new AssertionError("不应降级"); });
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t3", "三"), List.of()))
                .thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        service.streamTurn(in, bridge).collectList().block(Duration.ofSeconds(15));

        ArgumentCaptor<ToolMount> mountCap = ArgumentCaptor.forClass(ToolMount.class);
        verify(executor, times(2)).execute(any(), mountCap.capture(), any(), anyLong());
        assertThat(mountCap.getAllValues()).allSatisfy(m -> assertThat(m).isSameAs(mount));
    }

    // ---- 20. 主会话历史透传 Planner（Executor 上下文不带历史）；编排层无 store ----

    @Test
    void history_passedToPlannerContexts() {
        List<org.springframework.ai.chat.messages.Message> history =
                List.of(new UserMessage("上一轮对话"));
        OrchInput in = new OrchInput("run-1", "帮我分析日志", history, null, null,
                Duration.ofSeconds(110), true,
                () -> Flux.just("降级"), () -> "降级");
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "一")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        stubSynth("汇总");

        service.streamTurn(in, bridge).collectList().block(Duration.ofSeconds(15));

        ArgumentCaptor<PlannerContext> routeCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).route(routeCap.capture(), anyLong());
        assertThat(routeCap.getValue().history()).hasSize(1);
        ArgumentCaptor<PlannerContext> replanCap = ArgumentCaptor.forClass(PlannerContext.class);
        verify(planner).replan(replanCap.capture(), anyLong());
        assertThat(replanCap.getValue().history()).hasSize(1);
        assertThat(replanCap.getValue().userText()).isEqualTo("帮我分析日志");
    }

    // ---- 21. 审计 best-effort：audit 抛异常不影响编排 ----

    @Test
    void auditThrows_orchestrationContinues() {
        doThrow(new RuntimeException("audit DB down"))
                .when(audit).record(anyString(), any(), anyInt(), anyString(),
                        any(), any(), anyString(), anyLong(), any(), any());
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "一"), task("t2", "二")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong()))
                .thenReturn(new ReplanDecision.Next(task("t3", "三"), List.of("t2")))
                .thenReturn(new ReplanDecision.Final());
        stubSynth("最终", "答案");

        List<String> chunks = collect(service.streamTurn(streamInputNoDegrade(), bridge));

        assertThat(String.join("", chunks)).isEqualTo("最终答案");
    }

    // ---- 22. 取消：dispose 后不再发起新模型调用 ----

    @Test
    void cancel_stopsNewModelCalls() throws Exception {
        CountDownLatch execEntered = new CountDownLatch(1);
        CountDownLatch releaseExec = new CountDownLatch(1);
        when(planner.route(any(), anyLong()))
                .thenReturn(plan(task("t1", "慢一"), task("t2", "慢二")));
        when(executor.execute(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            execEntered.countDown();
            releaseExec.await();
            return ok("ok");
        });

        var disposable = service.streamTurn(streamInputNoDegrade(), bridge).subscribe();
        assertThat(execEntered.await(5, TimeUnit.SECONDS)).isTrue();
        disposable.dispose();
        releaseExec.countDown();
        Thread.sleep(300);

        verify(planner, never()).replan(any(), anyLong());
        verify(planner, never()).synthStream(any());
        assertThat(frames.stream().filter(f -> f instanceof PlanFrame).count()).isEqualTo(1);
    }

    // ---- 同步路径 happy：synth 聚合为纯文本 ----

    @Test
    void syncTurn_planHappy_aggregatesSynthReply() {
        when(planner.route(any(), anyLong())).thenReturn(plan(task("t1", "一")));
        when(executor.execute(any(), any(), any(), anyLong())).thenReturn(ok("ok"));
        when(planner.replan(any(), anyLong())).thenReturn(new ReplanDecision.Final());
        stubSynth("最终", "答案");

        OrchSyncOutcome outcome = service.syncTurn(new OrchInput("run-1", "帮我分析日志",
                List.of(), null, null, Duration.ofSeconds(55), false,
                () -> { throw new AssertionError("不应降级流"); },
                () -> { throw new AssertionError("不应降级"); }));

        assertThat(outcome.reply()).isEqualTo("最终答案");
    }

    // ---- 同步路径 direct：直接返回答案 ----

    @Test
    void syncTurn_direct_returnsAnswer() {
        when(planner.route(any(), anyLong())).thenReturn(new RouteDecision.Direct("直接回答"));

        OrchSyncOutcome outcome = service.syncTurn(syncInput());

        assertThat(outcome.reply()).isEqualTo("直接回答");
        verify(executor, never()).execute(any(), any(), any(), anyLong());
    }
}
