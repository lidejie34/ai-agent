package com.dj.ai.agentchat.dto;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代10/11：ChatRequest 知识库过滤字段——fastjson2 反序列化（带/不带新字段）、
 * 既有 3/4 参构造委托 null（迭代5 及之前调用点零改动回归）。
 * 迭代11：kbProject 单值升级为 kbProjects 数组（项目多选）。
 */
class ChatRequestKbJsonTest {

    @Test
    void deserialize_withKbFields_populated() {
        ChatRequest req = JSON.parseObject(
                "{\"message\":\"售后政策\",\"kbProjects\":[\"订单域\",\"物流域\"],\"kbTags\":[\"售后\",\"退货\"]}",
                ChatRequest.class);

        assertThat(req.message()).isEqualTo("售后政策");
        assertThat(req.kbProjects()).containsExactly("订单域", "物流域");
        assertThat(req.kbTags()).containsExactly("售后", "退货");
    }

    @Test
    void deserialize_withoutKbFields_nulls() {
        ChatRequest req = JSON.parseObject("{\"message\":\"你好\"}", ChatRequest.class);

        assertThat(req.kbProjects()).isNull();
        assertThat(req.kbTags()).isNull();
        assertThat(req.sdd()).isNull();
        assertThat(req.sessionId()).isNull();
    }

    @Test
    void deserialize_withToolScopeFields_populatedAndEmptyPreserved() {
        ChatRequest subset = JSON.parseObject(
                "{\"message\":\"查日志\",\"toolNames\":[\"analyze_log\"],\"mcpServers\":[\"easy-mysql\"]}",
                ChatRequest.class);
        assertThat(subset.toolNames()).containsExactly("analyze_log");
        assertThat(subset.mcpServers()).containsExactly("easy-mysql");

        // 显式空数组（全不挂）与缺省（null=全部）严格区分——三态不可归并
        ChatRequest explicitNone = JSON.parseObject(
                "{\"message\":\"查日志\",\"toolNames\":[],\"mcpServers\":[]}", ChatRequest.class);
        assertThat(explicitNone.toolNames()).isNotNull().isEmpty();
        assertThat(explicitNone.mcpServers()).isNotNull().isEmpty();
    }

    @Test
    void legacyConstructors_delegateNullKbFields() {
        ChatRequest three = new ChatRequest("m", List.of(), "sid");
        assertThat(three.sdd()).isNull();
        assertThat(three.kbProjects()).isNull();
        assertThat(three.kbTags()).isNull();

        ChatRequest four = new ChatRequest("m", List.of(), "sid", Boolean.TRUE);
        assertThat(four.kbProjects()).isNull();
        assertThat(four.kbTags()).isNull();

        ChatRequest two = new ChatRequest("m", List.of());
        assertThat(two.sessionId()).isNull();
        assertThat(two.kbProjects()).isNull();

        // 迭代12：6 参便捷构造（迭代10/11 调用点）委托 null 工具范围
        ChatRequest six = new ChatRequest("m", List.of(), "sid", null, List.of("订单域"), List.of());
        assertThat(six.kbProjects()).containsExactly("订单域");
        assertThat(six.toolNames()).isNull();
        assertThat(six.mcpServers()).isNull();
    }
}
