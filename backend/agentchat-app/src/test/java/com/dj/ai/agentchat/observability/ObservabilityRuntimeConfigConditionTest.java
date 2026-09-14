package com.dj.ai.agentchat.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代9（仿 {@code SddRuntimeConfigConditionTest}）：可观测性开关条件装配。
 * 默认（app.observability.enabled 缺省=false）：全部运行时 bean 缺席，应用上下文照常刷新；
 * enabled=true：filter 注册/MDC 初始化器/指标 Advisor/慢请求清单/端点全部在场；
 * properties 两种状态下均无条件绑定（ObservabilityPropsConfig）。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class ObservabilityRuntimeConfigDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void observabilityBeans_areAbsent_whenSwitchMissingOrFalse() {
        assertThat(context.getBeansOfType(ObservabilityRuntimeConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(ObservabilityMetricsAdvisor.class)).isEmpty();
        assertThat(context.getBeansOfType(SlowRequestTracker.class)).isEmpty();
        assertThat(context.getBeansOfType(SlowRequestsEndpoint.class)).isEmpty();
        assertThat(context.getBeansOfType(ObservabilityContextInitializer.class)).isEmpty();
        // TraceIdFilter 经 FilterRegistrationBean 注册，关闭态注册 bean 不存在。
        // （webMvcObservationFilter 是 actuator 引入的已知副作用——R-2 文档化接受，不在本断言范围）
        @SuppressWarnings("unchecked")
        Class<FilterRegistrationBean<?>> registrationType =
                (Class<FilterRegistrationBean<?>>) (Class<?>) FilterRegistrationBean.class;
        assertThat(context.getBeanNamesForType(registrationType))
                .doesNotContain("traceIdFilterRegistration");
        assertThat(context.getBeansOfType(TraceIdFilter.class)).isEmpty();
    }

    @Test
    void properties_areBoundUnconditionally_withDefaults() {
        ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isStreamUsageEnabled()).isFalse();
        assertThat(props.getSlowRequest().getThresholdMs()).isEqualTo(30_000);
        assertThat(props.getSlowRequest().getCapacity()).isEqualTo(100);
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.observability.enabled=true"
})
class ObservabilityRuntimeConfigEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void observabilityBeans_arePresent_whenEnabled() {
        assertThat(context.getBeansOfType(ObservabilityRuntimeConfig.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ObservabilityMetricsAdvisor.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SlowRequestTracker.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SlowRequestsEndpoint.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ObservabilityContextInitializer.class)).isNotEmpty();
        @SuppressWarnings("unchecked")
        Class<FilterRegistrationBean<?>> registrationType =
                (Class<FilterRegistrationBean<?>>) (Class<?>) FilterRegistrationBean.class;
        assertThat(context.getBeanNamesForType(registrationType))
                .contains("traceIdFilterRegistration");
    }

    @Test
    void properties_areBound_whenEnabled() {
        ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
        assertThat(props.isEnabled()).isTrue();
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.observability.enabled=true",
        "app.observability.slow-request.threshold-ms=5000",
        "app.observability.slow-request.capacity=7",
        "app.observability.stream-usage-enabled=true"
})
class ObservabilityPropertiesBindingTest {

    @Autowired
    private ObservabilityProperties props;

    @Autowired
    private SlowRequestTracker tracker;

    @Test
    void allKeys_bindFromConfig() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isStreamUsageEnabled()).isTrue();
        assertThat(props.getSlowRequest().getThresholdMs()).isEqualTo(5000);
        assertThat(props.getSlowRequest().getCapacity()).isEqualTo(7);
        // 慢请求清单 bean 按配置值构造
        assertThat(tracker.thresholdMs()).isEqualTo(5000);
        assertThat(tracker.capacity()).isEqualTo(7);
    }
}
