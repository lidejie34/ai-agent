package com.dj.ai.agentchat.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代9 FR-4：{@link SlowRequestTracker}——阈值边界、≤0 不记、环形淘汰、
 * 快照新→旧、并发写入线程安全。
 */
class SlowRequestTrackerTest {

    @Test
    void durationEqualToThreshold_isRecorded() {
        SlowRequestTracker tracker = new SlowRequestTracker(1000, 10);
        assertThat(tracker.recordIfSlow("t1", "/api/chat", 1000, "done")).isTrue();
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void durationOneMsBelowThreshold_isNotRecorded() {
        SlowRequestTracker tracker = new SlowRequestTracker(1000, 10);
        assertThat(tracker.recordIfSlow("t1", "/api/chat", 999, "done")).isFalse();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void nonPositiveThreshold_recordsNothing() {
        SlowRequestTracker zero = new SlowRequestTracker(0, 10);
        SlowRequestTracker negative = new SlowRequestTracker(-5, 10);
        assertThat(zero.recordIfSlow("t1", "/api/chat", 999_999, "done")).isFalse();
        assertThat(negative.recordIfSlow("t1", "/api/chat", 999_999, "done")).isFalse();
        assertThat(zero.size()).isZero();
        assertThat(negative.size()).isZero();
    }

    @Test
    void ringBufferEvictsOldestWhenFull() {
        SlowRequestTracker tracker = new SlowRequestTracker(1, 3);
        for (int i = 1; i <= 5; i++) {
            tracker.recordIfSlow("trace-" + i, "/api/chat", i * 10L, "done");
        }
        assertThat(tracker.size()).isEqualTo(3);
        List<SlowRequestTracker.SlowRequestEntry> snapshot = tracker.snapshot();
        // 新→旧：最新 trace-5 在前；最旧两条（trace-1/2）已淘汰
        assertThat(snapshot).extracting(SlowRequestTracker.SlowRequestEntry::traceId)
                .containsExactly("trace-5", "trace-4", "trace-3");
    }

    @Test
    void snapshotIsNewestFirst_andIndependentCopy() {
        SlowRequestTracker tracker = new SlowRequestTracker(1, 10);
        tracker.recordIfSlow("a", "/api/chat", 10, "done");
        tracker.recordIfSlow("b", "/api/chat/stream", 20, "error");

        List<SlowRequestTracker.SlowRequestEntry> snapshot = tracker.snapshot();
        assertThat(snapshot).extracting(SlowRequestTracker.SlowRequestEntry::traceId)
                .containsExactly("b", "a");
        SlowRequestTracker.SlowRequestEntry latest = snapshot.get(0);
        assertThat(latest.path()).isEqualTo("/api/chat/stream");
        assertThat(latest.outcome()).isEqualTo("error");
        assertThat(latest.durationMs()).isEqualTo(20);
        assertThat(latest.startTimeIso()).isNotBlank();
        // 快照是独立副本：继续写入不影响已取快照
        tracker.recordIfSlow("c", "/api/chat", 30, "done");
        assertThat(snapshot).hasSize(2);
    }

    @Test
    void concurrentWrites_areThreadSafe_andBounded() throws InterruptedException {
        int capacity = 50;
        SlowRequestTracker tracker = new SlowRequestTracker(1, capacity);
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        tracker.recordIfSlow("trace-" + (base + i), "/api/chat", 10, "done");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(tracker.size()).isEqualTo(capacity);
        assertThat(tracker.snapshot()).hasSize(capacity);
    }

    @Test
    void accessorsExposeThresholdAndCapacity() {
        SlowRequestTracker tracker = new SlowRequestTracker(30_000, 100);
        assertThat(tracker.thresholdMs()).isEqualTo(30_000);
        assertThat(tracker.capacity()).isEqualTo(100);
    }
}
