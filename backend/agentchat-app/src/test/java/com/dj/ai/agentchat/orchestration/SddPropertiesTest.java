package com.dj.ai.agentchat.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：{@link SddProperties} 缺省默认值（AC-56）——enabled=false、max-rounds=6、
 * max-tasks=8、max-consecutive-failures=2、executor.max-result-chars=2000、
 * SSE 总预算 110s、同步总预算 55s、task-timeout 缺省 null（继承 HTTP 超时）、
 * planner/executor model 与 system-prompt 空白（继承主模型 / 回退内置常量）。
 */
class SddPropertiesTest {

    @Test
    void defaults_matchContract() {
        SddProperties props = new SddProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getMaxRounds()).isEqualTo(6);
        assertThat(props.getMaxTasks()).isEqualTo(8);
        assertThat(props.getMaxConsecutiveFailures()).isEqualTo(2);
        assertThat(props.getTotalBudgetSse()).isEqualTo(Duration.ofSeconds(110));
        assertThat(props.getTotalBudgetSync()).isEqualTo(Duration.ofSeconds(55));
        assertThat(props.getTaskTimeout()).isNull();

        assertThat(props.getPlanner().getModel()).isEmpty();
        assertThat(props.getPlanner().getSystemPrompt()).isEmpty();
        assertThat(props.getPlanner().getTemperature()).isNull();

        assertThat(props.getExecutor().getModel()).isEmpty();
        assertThat(props.getExecutor().getSystemPrompt()).isEmpty();
        assertThat(props.getExecutor().getTemperature()).isNull();
        assertThat(props.getExecutor().getMaxResultChars()).isEqualTo(2000);
    }

    @Test
    void setters_roundTrip() {
        SddProperties props = new SddProperties();
        props.setEnabled(true);
        props.setMaxRounds(3);
        props.setMaxTasks(5);
        props.setMaxConsecutiveFailures(1);
        props.setTotalBudgetSse(Duration.ofSeconds(90));
        props.setTotalBudgetSync(Duration.ofSeconds(40));
        props.setTaskTimeout(Duration.ofSeconds(30));
        props.getPlanner().setModel("planner-model");
        props.getPlanner().setSystemPrompt("自定义规划提示词");
        props.getPlanner().setTemperature(0.2);
        props.getExecutor().setModel("executor-model");
        props.getExecutor().setSystemPrompt("自定义执行提示词");
        props.getExecutor().setTemperature(0.1);
        props.getExecutor().setMaxResultChars(1234);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxRounds()).isEqualTo(3);
        assertThat(props.getMaxTasks()).isEqualTo(5);
        assertThat(props.getMaxConsecutiveFailures()).isEqualTo(1);
        assertThat(props.getTotalBudgetSse()).isEqualTo(Duration.ofSeconds(90));
        assertThat(props.getTotalBudgetSync()).isEqualTo(Duration.ofSeconds(40));
        assertThat(props.getTaskTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.getPlanner().getModel()).isEqualTo("planner-model");
        assertThat(props.getPlanner().getSystemPrompt()).isEqualTo("自定义规划提示词");
        assertThat(props.getPlanner().getTemperature()).isEqualTo(0.2);
        assertThat(props.getExecutor().getModel()).isEqualTo("executor-model");
        assertThat(props.getExecutor().getSystemPrompt()).isEqualTo("自定义执行提示词");
        assertThat(props.getExecutor().getTemperature()).isEqualTo(0.1);
        assertThat(props.getExecutor().getMaxResultChars()).isEqualTo(1234);
    }
}
