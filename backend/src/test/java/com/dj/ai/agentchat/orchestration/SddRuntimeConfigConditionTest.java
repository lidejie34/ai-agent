package com.dj.ai.agentchat.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：编排开关条件装配（AC-1/AC-4/AC-56/AC-59）。
 * 默认（app.sdd.enabled 缺省=false）：SddProperties/SddRuntimeConfig 均不装配，
 * 应用上下文照常刷新（编排 bean 缺席 = 迭代4 路径零触达）；
 * enabled=true：配置与属性 bean 装配，缺省值正确；
 * 全键可经配置覆盖绑定（APP_SDD_* env 同构）。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class SddRuntimeConfigDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void orchestrationBeans_areAbsent_whenSwitchMissingOrFalse() {
        assertThat(context.getBeansOfType(SddProperties.class)).isEmpty();
        assertThat(context.getBeansOfType(SddRuntimeConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(OrchestrationService.class)).isEmpty();
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.sdd.enabled=true"
})
class SddRuntimeConfigEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void orchestrationBeans_arePresent_whenEnabled_withDefaults() {
        SddProperties props = context.getBean(SddProperties.class);
        assertThat(props).isNotNull();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxRounds()).isEqualTo(6);
        assertThat(props.getMaxTasks()).isEqualTo(8);
        assertThat(props.getMaxConsecutiveFailures()).isEqualTo(2);
        assertThat(props.getTotalBudgetSse()).isEqualTo(Duration.ofSeconds(110));
        assertThat(props.getTotalBudgetSync()).isEqualTo(Duration.ofSeconds(55));
        assertThat(props.getTaskTimeout()).isNull();
        assertThat(props.getExecutor().getMaxResultChars()).isEqualTo(2000);
        assertThat(context.getBeansOfType(SddRuntimeConfig.class)).isNotEmpty();
    }
}

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.sdd.enabled=true",
        "app.sdd.max-rounds=3",
        "app.sdd.max-tasks=5",
        "app.sdd.max-consecutive-failures=1",
        "app.sdd.total-budget-sse=90s",
        "app.sdd.total-budget-sync=40s",
        "app.sdd.task-timeout=30s",
        "app.sdd.planner.model=planner-model-x",
        "app.sdd.planner.system-prompt=自定义规划",
        "app.sdd.planner.temperature=0.2",
        "app.sdd.executor.model=executor-model-x",
        "app.sdd.executor.system-prompt=自定义执行",
        "app.sdd.executor.temperature=0.1",
        "app.sdd.executor.max-result-chars=777"
})
class SddPropertiesBindingTest {

    @Autowired
    private SddProperties props;

    @Test
    void allKeys_bindFromConfig() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxRounds()).isEqualTo(3);
        assertThat(props.getMaxTasks()).isEqualTo(5);
        assertThat(props.getMaxConsecutiveFailures()).isEqualTo(1);
        assertThat(props.getTotalBudgetSse()).isEqualTo(Duration.ofSeconds(90));
        assertThat(props.getTotalBudgetSync()).isEqualTo(Duration.ofSeconds(40));
        assertThat(props.getTaskTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.getPlanner().getModel()).isEqualTo("planner-model-x");
        assertThat(props.getPlanner().getSystemPrompt()).isEqualTo("自定义规划");
        assertThat(props.getPlanner().getTemperature()).isEqualTo(0.2);
        assertThat(props.getExecutor().getModel()).isEqualTo("executor-model-x");
        assertThat(props.getExecutor().getSystemPrompt()).isEqualTo("自定义执行");
        assertThat(props.getExecutor().getTemperature()).isEqualTo(0.1);
        assertThat(props.getExecutor().getMaxResultChars()).isEqualTo(777);
    }
}
