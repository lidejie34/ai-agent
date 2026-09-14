package com.dj.ai.agentchat.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性运行时装配（迭代9）：整体受 {@code app.observability.enabled}
 * （默认 false）开关收口。
 *
 * <p>范式复刻 {@code SddRuntimeConfig}/{@code ToolRuntimeConfig}：条件装配 +
 * 全部 bean 由本类 {@code @Bean} 显式装配（构造型注解不与条件装配混用）。
 * 开关关闭时：TraceIdFilter 不注册、指标 Advisor 不创建（ChatClientConfig 经
 * ObjectProvider 拿不到 → 不挂载）、慢请求清单/端点/MDC 传播注册器均缺席——
 * 过滤器链、Advisor 链、actuator 暴露面与迭代8 逐字节一致（AC-1）。
 *
 * <p>properties（{@link ObservabilityProperties}）由 {@code ObservabilityPropsConfig}
 * 无条件绑定恒在场，本配置仅消费。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.observability", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class ObservabilityRuntimeConfig {

    /**
     * traceId 过滤器注册：全路径（含 /api/admin/**），order 在编码过滤器之后、
     * 全部业务之前。filter 自身零日志；MDC 在 finally 清理（NFR-5）。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * MDC 跨线程传递注册器：MDC ThreadLocalAccessor + Reactor 自动上下文传播
     （三 daemon 池的 ContextExecutorService 包裹在各 RuntimeConfig 内按本开关条件包裹）。
     */
    @Bean
    public ObservabilityContextInitializer observabilityContextInitializer() {
        return new ObservabilityContextInitializer();
    }

    /**
     * 模型耗时/token 指标 Advisor：持 Boot actuator 自动配置的内存 MeterRegistry
     （CompositeMeterRegistry，无远程导出，NFR-1）；经 ChatClientConfig 条件挂载。
     */
    @Bean
    public ObservabilityMetricsAdvisor observabilityMetricsAdvisor(
            MeterRegistry meterRegistry,
            @Value("${spring.ai.openai.chat.options.model:}") String configuredModel,
            ObservabilityProperties properties) {
        // stream-usage 实证子开关随构造传入（本配置仅总开关开启时激活 → 双重门控）
        return new ObservabilityMetricsAdvisor(meterRegistry, configuredModel,
                properties.isStreamUsageEnabled());
    }

    /** 慢请求环形清单（阈值 ≤0 不记录；写满淘汰最旧）。 */
    @Bean
    public SlowRequestTracker slowRequestTracker(ObservabilityProperties properties) {
        return new SlowRequestTracker(properties.getSlowRequest().getThresholdMs(),
                properties.getSlowRequest().getCapacity());
    }

    /**
     * 慢请求只读端点：bean 条件装配 + web exposure 白名单双保险（R-2）。
     */
    @Bean
    public SlowRequestsEndpoint slowRequestsEndpoint(SlowRequestTracker slowRequestTracker) {
        return new SlowRequestsEndpoint(slowRequestTracker);
    }
}
