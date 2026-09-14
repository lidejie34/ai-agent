package com.dj.ai.agentchat.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性配置（迭代9，{@code app.observability}）：traceId / token 用量 /
 * 模型耗时指标 / 慢请求清单。
 *
 * <p>总开关默认关闭（配置级回滚路径）：关闭时运行时 bean 零装配、actuator 端点
 * 零暴露，行为与迭代8 逐字节一致。properties 经 {@code ObservabilityPropsConfig}
 * <b>无条件绑定</b>（仿 ChatEvidenceConfig）——ChatService / PlannerClient /
 * ExecutorClient 构造注入不需要条件装配；运行时 bean 由
 * {@link ObservabilityRuntimeConfig} 条件装配。
 */
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilityProperties {

    /** 可观测性总开关，默认关闭（生产/QA 用环境变量 APP_OBSERVABILITY_ENABLED 开启）。 */
    private boolean enabled = false;

    /** 慢请求清单配置。 */
    private SlowRequest slowRequest = new SlowRequest();

    /**
     * 流式 token 实证子开关（R-1，默认 false）：true 且总开关开启时，流式请求带
     * {@code stream_options.include_usage}；方舟不认/报错则保持 false——拿不到 usage
     * 时行为 = 显式 unavailable 降级，不做本地估算。
     */
    private boolean streamUsageEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SlowRequest getSlowRequest() {
        return slowRequest;
    }

    public void setSlowRequest(SlowRequest slowRequest) {
        this.slowRequest = slowRequest;
    }

    public boolean isStreamUsageEnabled() {
        return streamUsageEnabled;
    }

    public void setStreamUsageEnabled(boolean streamUsageEnabled) {
        this.streamUsageEnabled = streamUsageEnabled;
    }

    /** 慢请求清单：HTTP 请求级（SSE=订阅到流结束）超阈值记录。 */
    public static class SlowRequest {

        /** 慢请求阈值（毫秒）；≤0 = 不记录（FR-4.2）。 */
        private long thresholdMs = 30_000;

        /** 有界内存环形清单容量（重启丢失，本地自用）。 */
        private int capacity = 100;

        public long getThresholdMs() {
            return thresholdMs;
        }

        public void setThresholdMs(long thresholdMs) {
            this.thresholdMs = thresholdMs;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }
}
