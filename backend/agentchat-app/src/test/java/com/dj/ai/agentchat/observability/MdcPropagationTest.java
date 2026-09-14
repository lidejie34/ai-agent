package com.dj.ai.agentchat.observability;

import com.dj.ai.agentchat.util.TraceIds;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代9 FR-1.5 / NFR-5：MDC 跨池传递语义——
 * {@link ObservabilityContextInitializer.MdcThreadLocalAccessor} 注册进
 * {@link ContextRegistry} 后，{@link ContextExecutorService#wrap} 包裹的池
 * 在 submit 时快照提交线程 MDC、任务执行前恢复、执行后复位（不串号、不泄漏）。
 *
 * <p>测试用独立 {@link ContextRegistry} 实例（不触碰全局单例），语义与生产装配一致。
 */
class MdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static ContextSnapshotFactory factoryWithMdcAccessor() {
        ContextRegistry registry = new ContextRegistry();
        registry.registerThreadLocalAccessor(
                new ObservabilityContextInitializer.MdcThreadLocalAccessor());
        return ContextSnapshotFactory.builder().contextRegistry(registry).build();
    }

    @Test
    void mdcSnapshotIsVisibleInWrappedPool_andResetAfterExecution() throws Exception {
        ExecutorService raw = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-mdc-pool");
            t.setDaemon(true);
            return t;
        });
        ExecutorService wrapped = ContextExecutorService.wrap(raw, factoryWithMdcAccessor()::captureAll);
        try {
            MDC.put(TraceIds.MDC_KEY, "trace-from-submit-thread");
            AtomicReference<String> seenInTask = new AtomicReference<>();

            Future<?> future = wrapped.submit(() -> seenInTask.set(MDC.get(TraceIds.MDC_KEY)));
            future.get(5, TimeUnit.SECONDS);

            // 提交线程的 MDC 在执行线程可见
            assertThat(seenInTask.get()).isEqualTo("trace-from-submit-thread");

            // 执行后池线程被复位：同一池再跑无快照语境的任务不应看到上一次的值。
            // （改提交线程 MDC 后再次提交，看到的是新值而非残留）
            MDC.put(TraceIds.MDC_KEY, "trace-second-submit");
            AtomicReference<String> seenSecond = new AtomicReference<>();
            wrapped.submit(() -> seenSecond.set(MDC.get(TraceIds.MDC_KEY))).get(5, TimeUnit.SECONDS);
            assertThat(seenSecond.get()).isEqualTo("trace-second-submit");
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void emptyMdcAtSubmit_taskSeesNoTraceId() throws Exception {
        ExecutorService raw = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-mdc-pool-empty");
            t.setDaemon(true);
            return t;
        });
        ExecutorService wrapped = ContextExecutorService.wrap(raw, factoryWithMdcAccessor()::captureAll);
        try {
            // 提交线程 MDC 为空（关闭态语义）
            AtomicReference<String> seenInTask = new AtomicReference<>("unset");
            wrapped.submit(() -> seenInTask.set(MDC.get(TraceIds.MDC_KEY))).get(5, TimeUnit.SECONDS);
            assertThat(seenInTask.get()).isNull();
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void accessor_setGetReset_roundTrip() {
        ObservabilityContextInitializer.MdcThreadLocalAccessor accessor =
                new ObservabilityContextInitializer.MdcThreadLocalAccessor();
        MDC.put(TraceIds.MDC_KEY, "round-trip-id");
        java.util.Map<String, String> snapshot = accessor.getValue();

        MDC.clear();
        assertThat(accessor.getValue()).isNull();

        accessor.setValue(snapshot);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo("round-trip-id");

        accessor.reset();
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }
}
