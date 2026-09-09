package com.dj.ai.agentchat.orchestration.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2：内置默认提示词常量（AC-57 空白回退）与 Executor 用户消息模板（AC-17/AC-28）。
 */
class SddPromptsTest {

    @Test
    void defaultSystemPrompts_areNonBlankAndContainContract() {
        assertThat(SddPrompts.PLANNER_DEFAULT_SYSTEM).isNotBlank();
        assertThat(SddPrompts.PLANNER_DEFAULT_SYSTEM).contains("\"mode\"");
        assertThat(SddPrompts.PLANNER_DEFAULT_SYSTEM).contains("\"action\"");
        assertThat(SddPrompts.PLANNER_DEFAULT_SYSTEM).contains("direct");
        assertThat(SddPrompts.PLANNER_DEFAULT_SYSTEM).contains("plan");

        assertThat(SddPrompts.EXECUTOR_DEFAULT_SYSTEM).isNotBlank();
        assertThat(SddPrompts.EXECUTOR_DEFAULT_SYSTEM).contains("\"ok\"");
        assertThat(SddPrompts.EXECUTOR_DEFAULT_SYSTEM).contains("result");

        assertThat(SddPrompts.SYNTH_SUFFIX).isNotBlank();
        assertThat(SddPrompts.SYNTH_SUFFIX).contains("汇总");
    }

    @Test
    void routeRetryInstruction_tellsModelToReoutputJsonOnly() {
        assertThat(SddPrompts.ROUTE_RETRY_INSTRUCTION).contains("JSON");
    }

    @Test
    void forceFinishNote_containsCapReasonPlaceholder() {
        String note = SddPrompts.forceFinishNote("规划轮次上限");
        assertThat(note).contains("规划轮次上限");
        assertThat(note).contains("已达编排上限");
    }

    @Test
    void executorUserText_containsRequestSummaryTitleGoal() {
        String text = SddPrompts.executorUserText("分析日志并给建议", "扫描错误日志", "统计 Top 错误");

        assertThat(text).contains("分析日志并给建议");
        assertThat(text).contains("扫描错误日志");
        assertThat(text).contains("统计 Top 错误");
    }

    @Test
    void executorUserText_truncatesUserRequestTo500Chars() {
        String longRequest = "x".repeat(900);
        String text = SddPrompts.executorUserText(longRequest, "标题", "目标描述");

        assertThat(text).contains("标题");
        // 原始请求截断 500 字，模板其余部分（标题/目标/固定文案）另算
        assertThat(text).doesNotContain("x".repeat(501));
        assertThat(text.length()).isLessThan(900);
    }

    @Test
    void plannerReplanUserText_containsOriginalRequestAndObservations() {
        String text = SddPrompts.replanUserText("原始请求", "原始计划摘要",
                java.util.List.of("观察1：成功", "观察2：失败"));
        assertThat(text).contains("原始请求");
        assertThat(text).contains("原始计划摘要");
        assertThat(text).contains("观察1：成功");
        assertThat(text).contains("观察2：失败");
    }
}
