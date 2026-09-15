package com.dj.ai.agentchat.dto;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代10：ChatRequest 知识库过滤字段——fastjson2 反序列化（带/不带新字段）、
 * 既有 3/4 参构造委托 null（迭代5 及之前调用点零改动回归）。
 */
class ChatRequestKbJsonTest {

    @Test
    void deserialize_withKbFields_populated() {
        ChatRequest req = JSON.parseObject(
                "{\"message\":\"售后政策\",\"kbProject\":\"订单域\",\"kbTags\":[\"售后\",\"退货\"]}",
                ChatRequest.class);

        assertThat(req.message()).isEqualTo("售后政策");
        assertThat(req.kbProject()).isEqualTo("订单域");
        assertThat(req.kbTags()).containsExactly("售后", "退货");
    }

    @Test
    void deserialize_withoutKbFields_nulls() {
        ChatRequest req = JSON.parseObject("{\"message\":\"你好\"}", ChatRequest.class);

        assertThat(req.kbProject()).isNull();
        assertThat(req.kbTags()).isNull();
        assertThat(req.sdd()).isNull();
        assertThat(req.sessionId()).isNull();
    }

    @Test
    void legacyConstructors_delegateNullKbFields() {
        ChatRequest three = new ChatRequest("m", List.of(), "sid");
        assertThat(three.sdd()).isNull();
        assertThat(three.kbProject()).isNull();
        assertThat(three.kbTags()).isNull();

        ChatRequest four = new ChatRequest("m", List.of(), "sid", Boolean.TRUE);
        assertThat(four.kbProject()).isNull();
        assertThat(four.kbTags()).isNull();

        ChatRequest two = new ChatRequest("m", List.of());
        assertThat(two.sessionId()).isNull();
        assertThat(two.kbProject()).isNull();
    }
}
