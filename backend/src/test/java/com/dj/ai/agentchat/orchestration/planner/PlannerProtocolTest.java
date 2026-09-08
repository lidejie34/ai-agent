package com.dj.ai.agentchat.orchestration.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2：Planner 三轮 JSON 契约纯函数解析（AC-10/AC-13/AC-14）——
 * 代码块围栏/散文包裹平衡括号提取、fastjson2 解析、字段校验、taskId 补齐去重、
 * 超 maxTasks 截断标记；replan next/final/skipped；任何坏输入 → Unparseable（不抛出）。
 */
class PlannerProtocolTest {

    // ---- route ----

    @Test
    void parseRoute_direct_normal() {
        RouteDecision d = PlannerProtocol.parseRoute(
                "{\"mode\":\"direct\",\"answer\":\"你好！有什么可以帮你的？\"}", 8);

        assertThat(d).isInstanceOf(RouteDecision.Direct.class);
        assertThat(((RouteDecision.Direct) d).answer()).isEqualTo("你好！有什么可以帮你的？");
    }

    @Test
    void parseRoute_plan_normal_withTasksAndGoals() {
        RouteDecision d = PlannerProtocol.parseRoute(
                "{\"mode\":\"plan\",\"tasks\":[" +
                        "{\"taskId\":\"t1\",\"title\":\"扫描错误日志\",\"goal\":\"分析近 24h 错误并输出 Top 列表\"}," +
                        "{\"taskId\":\"t2\",\"title\":\"关联订单问题\",\"goal\":\"基于 t1 结果筛选订单相关错误\"}" +
                        "]}", 8);

        assertThat(d).isInstanceOf(RouteDecision.Plan.class);
        RouteDecision.Plan plan = (RouteDecision.Plan) d;
        assertThat(plan.truncated()).isFalse();
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks().get(0).taskId()).isEqualTo("t1");
        assertThat(plan.tasks().get(0).title()).isEqualTo("扫描错误日志");
        assertThat(plan.tasks().get(0).goal()).contains("近 24h");
        assertThat(plan.tasks().get(1).taskId()).isEqualTo("t2");
    }

    @Test
    void parseRoute_jsonFence_extracted() {
        String raw = "好的，这是我的计划：\n```json\n{\"mode\":\"direct\",\"answer\":\"围栏内回答\"}\n```\n以上。";
        RouteDecision d = PlannerProtocol.parseRoute(raw, 8);

        assertThat(d).isInstanceOf(RouteDecision.Direct.class);
        assertThat(((RouteDecision.Direct) d).answer()).isEqualTo("围栏内回答");
    }

    @Test
    void parseRoute_proseWrapped_balancedBraces_extracted() {
        String raw = "我认为应该直接回答。{\"mode\":\"direct\",\"answer\":\"括号提取\"} 希望对你有帮助。";
        RouteDecision d = PlannerProtocol.parseRoute(raw, 8);

        assertThat(d).isInstanceOf(RouteDecision.Direct.class);
        assertThat(((RouteDecision.Direct) d).answer()).isEqualTo("括号提取");
    }

    @Test
    void parseRoute_badJson_unparseable() {
        assertThat(PlannerProtocol.parseRoute("{\"mode\":\"direct\",oops", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseRoute("完全没有 JSON 的散文", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseRoute(null, 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
    }

    @Test
    void parseRoute_missingOrUnknownMode_unparseable() {
        assertThat(PlannerProtocol.parseRoute("{\"answer\":\"没有 mode\"}", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseRoute("{\"mode\":\"chat\",\"answer\":\"x\"}", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
    }

    @Test
    void parseRoute_direct_blankAnswer_unparseable() {
        assertThat(PlannerProtocol.parseRoute("{\"mode\":\"direct\",\"answer\":\"   \"}", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
    }

    @Test
    void parseRoute_plan_emptyOrMissingTasks_unparseable() {
        assertThat(PlannerProtocol.parseRoute("{\"mode\":\"plan\",\"tasks\":[]}", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseRoute("{\"mode\":\"plan\"}", 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
    }

    @Test
    void parseRoute_plan_taskWithBlankTitle_unparseable() {
        String raw = "{\"mode\":\"plan\",\"tasks\":[{\"taskId\":\"t1\",\"title\":\"   \"}]}";
        assertThat(PlannerProtocol.parseRoute(raw, 8))
                .isInstanceOf(RouteDecision.Unparseable.class);
    }

    @Test
    void parseRoute_missingTaskIds_assignedSequentially() {
        String raw = "{\"mode\":\"plan\",\"tasks\":[" +
                "{\"title\":\"任务一\",\"goal\":\"目标一\"}," +
                "{\"title\":\"任务二\",\"goal\":\"目标二\"}]}";
        RouteDecision d = PlannerProtocol.parseRoute(raw, 8);

        assertThat(((RouteDecision.Plan) d).tasks()).extracting(TaskSpec::taskId)
                .containsExactly("t1", "t2");
    }

    @Test
    void parseRoute_duplicateAndInvalidTaskIds_dedupedAndNormalized() {
        String raw = "{\"mode\":\"plan\",\"tasks\":[" +
                "{\"taskId\":\"dup\",\"title\":\"A\",\"goal\":\"g1\"}," +
                "{\"taskId\":\"dup\",\"title\":\"B\",\"goal\":\"g2\"}," +
                "{\"taskId\":\"非法 id 有空格\",\"title\":\"C\",\"goal\":\"g3\"}]}";
        RouteDecision d = PlannerProtocol.parseRoute(raw, 8);

        List<String> ids = ((RouteDecision.Plan) d).tasks().stream().map(TaskSpec::taskId).toList();
        // 第一个 dup 保留；重复 dup 与非法 id 回退 t2/t3
        assertThat(ids).containsExactly("dup", "t2", "t3");
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void parseRoute_moreTasksThanMax_truncatedToMax_withFlag() {
        String raw = "{\"mode\":\"plan\",\"tasks\":[" +
                "{\"taskId\":\"t1\",\"title\":\"1\",\"goal\":\"g\"}," +
                "{\"taskId\":\"t2\",\"title\":\"2\",\"goal\":\"g\"}," +
                "{\"taskId\":\"t3\",\"title\":\"3\",\"goal\":\"g\"}]}";
        RouteDecision d = PlannerProtocol.parseRoute(raw, 2);

        RouteDecision.Plan plan = (RouteDecision.Plan) d;
        assertThat(plan.truncated()).isTrue();
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks()).extracting(TaskSpec::taskId).containsExactly("t1", "t2");
    }

    // ---- replan ----

    @Test
    void parseReplan_next_withTaskAndSkipped() {
        String raw = "{\"action\":\"next\"," +
                "\"task\":{\"taskId\":\"t3\",\"title\":\"汇总排查建议\",\"goal\":\"给出 3 条建议\"}," +
                "\"skipped\":[\"t2\"]}";
        ReplanDecision d = PlannerProtocol.parseReplan(raw);

        assertThat(d).isInstanceOf(ReplanDecision.Next.class);
        ReplanDecision.Next next = (ReplanDecision.Next) d;
        assertThat(next.task().title()).isEqualTo("汇总排查建议");
        assertThat(next.task().goal()).isEqualTo("给出 3 条建议");
        assertThat(next.skippedTaskIds()).containsExactly("t2");
    }

    @Test
    void parseReplan_next_skippedMissingOrEmpty_defaultsEmpty() {
        ReplanDecision d = PlannerProtocol.parseReplan(
                "{\"action\":\"next\",\"task\":{\"title\":\"再做一件\",\"goal\":\"goal 文本足够长\"}}");
        assertThat(d).isInstanceOf(ReplanDecision.Next.class);
        assertThat(((ReplanDecision.Next) d).skippedTaskIds()).isEmpty();
    }

    @Test
    void parseReplan_final() {
        assertThat(PlannerProtocol.parseReplan("{\"action\":\"final\"}"))
                .isInstanceOf(ReplanDecision.Final.class);
        assertThat(PlannerProtocol.parseReplan("好的，收尾吧。{\"action\":\"final\"}"))
                .isInstanceOf(ReplanDecision.Final.class);
    }

    @Test
    void parseReplan_invalidActionOrMissingTask_unparseable() {
        assertThat(PlannerProtocol.parseReplan("{\"action\":\"pause\"}"))
                .isInstanceOf(ReplanDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseReplan("{\"action\":\"next\"}"))
                .isInstanceOf(ReplanDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseReplan("{\"action\":\"next\",\"task\":{\"title\":\"  \",\"goal\":\"g\"}}"))
                .isInstanceOf(ReplanDecision.Unparseable.class);
        assertThat(PlannerProtocol.parseReplan("not json"))
                .isInstanceOf(ReplanDecision.Unparseable.class);
    }
}
