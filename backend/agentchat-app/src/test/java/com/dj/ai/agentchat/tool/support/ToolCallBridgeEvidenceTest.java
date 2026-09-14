package com.dj.ai.agentchat.tool.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T2（迭代8）：{@link ToolCallBridge} 证据挂载——remember 首次写缓存记 1 条证据；
 * 同 dedupKey 二次 remember（putIfAbsent 未覆盖）不重复记；未挂收集器行为不变；
 * record 异常不上浮；单参紧凑构造 CachedOutcome 兼容（toolName==null 不记录）。
 */
class ToolCallBridgeEvidenceTest {

    private static ToolCallBridge.CachedOutcome outcome(String toolName) {
        return new ToolCallBridge.CachedOutcome(
                "<tool-result>\nbody\n</tool-result>", toolName, "SUCCESS", 12L,
                "{\"a\":1}", "body");
    }

    @Test
    void firstRemember_recordsExactlyOneEvidenceEntry() {
        ToolCallBridge bridge = new ToolCallBridge();
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        bridge.attachEvidenceCollector(collector);

        bridge.remember("k1", outcome("demo_tool"));

        String rendered = collector.renderEvidence();
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("demo_tool | SUCCESS | 12ms | 入参: {\"a\":1} | 结果: body");
    }

    @Test
    void secondRememberSameDedupKey_doesNotDuplicateEvidence() {
        ToolCallBridge bridge = new ToolCallBridge();
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        bridge.attachEvidenceCollector(collector);

        bridge.remember("k1", outcome("tool_a"));
        bridge.remember("k1", outcome("tool_b")); // putIfAbsent 未覆盖 → 不记

        String rendered = collector.renderEvidence();
        assertThat(rendered).contains("tool_a");
        assertThat(rendered).doesNotContain("tool_b");
        // 恰好一行（无行分隔符）
        assertThat(rendered).doesNotContain("\n");
    }

    @Test
    void rememberWithoutCollector_behavesUnchanged() {
        ToolCallBridge bridge = new ToolCallBridge();

        assertThatCode(() -> bridge.remember("k1", outcome("demo_tool")))
                .doesNotThrowAnyException();
        // 幂等缓存语义不变：lookup 命中返回原文本
        assertThat(bridge.lookup("k1").resultText()).isEqualTo("<tool-result>\nbody\n</tool-result>");
    }

    @Test
    void recordThrowing_doesNotPropagate() {
        ToolCallBridge bridge = new ToolCallBridge();
        bridge.attachEvidenceCollector(new ToolEvidenceCollector(800, 4000) {
            @Override
            public void record(Entry entry) {
                throw new RuntimeException("模拟收集器故障");
            }
        });

        assertThatCode(() -> bridge.remember("k1", outcome("demo_tool")))
                .doesNotThrowAnyException();
        // 缓存写入不受证据失败影响
        assertThat(bridge.lookup("k1")).isNotNull();
    }

    @Test
    void compactConstructorOutcome_toolNameNull_notRecorded() {
        ToolCallBridge bridge = new ToolCallBridge();
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        bridge.attachEvidenceCollector(collector);

        // 既有单参紧凑构造（无证据语义）——兼容路径不记证据
        bridge.remember("k1", new ToolCallBridge.CachedOutcome("<tool-result>\nx\n</tool-result>"));

        assertThat(collector.isEmpty()).isTrue();
        assertThat(collector.renderEvidence()).isNull();
        assertThat(bridge.lookup("k1").resultText()).isEqualTo("<tool-result>\nx\n</tool-result>");
    }

    @Test
    void lookupHit_earlyReturn_neverTouchesCollector() {
        ToolCallBridge bridge = new ToolCallBridge();
        ToolEvidenceCollector collector = new ToolEvidenceCollector(800, 4000);
        bridge.attachEvidenceCollector(collector);

        bridge.remember("k1", outcome("demo_tool"));
        // 命中路径（Flux.defer 重订阅重跑工具的早退点）：只读缓存，与收集器零交互
        ToolCallBridge.CachedOutcome cached = bridge.lookup("k1");
        assertThat(cached).isNotNull();
        assertThat(cached.toolName()).isEqualTo("demo_tool");
        assertThat(collector.renderEvidence().split("\n")).hasSize(1);
    }
}
