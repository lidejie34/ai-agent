package com.dj.ai.agentchat.tool.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.tool.support.ToolEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9：{@link ToolEventFrame} fastjson2 序列化契约——
 * null 字段省略（started 无 durationMs/error；succeeded 无 error），
 * status 为线网小写字面量；SSE 帧经 fastjson2 转换器输出，字段名稳定。
 */
class ToolEventFrameTest {

    @Test
    void startedFrame_serializesWithoutNullFields() {
        ToolEventFrame frame = ToolEventFrame.from(ToolEvent.started(
                "req-1|demo_builtin_tool|abc123", "demo_builtin_tool", "{\"minutes\":30}"));

        JSONObject json = JSON.parseObject(JSON.toJSONString(frame));

        assertThat(json.getString("callId")).isEqualTo("req-1|demo_builtin_tool|abc123");
        assertThat(json.getString("tool")).isEqualTo("demo_builtin_tool");
        assertThat(json.getString("arguments")).isEqualTo("{\"minutes\":30}");
        assertThat(json.getString("status")).isEqualTo("started");
        // started 帧不带耗时与错误——fastjson2 默认省略 null 字段
        assertThat(json.containsKey("durationMs")).isFalse();
        assertThat(json.containsKey("error")).isFalse();
    }

    @Test
    void succeededFrame_hasDurationMs_noError() {
        ToolEventFrame frame = ToolEventFrame.from(ToolEvent.terminal(
                "req-1|demo_builtin_tool|abc123", "demo_builtin_tool", "{\"minutes\":30}",
                true, 42L, null));

        JSONObject json = JSON.parseObject(JSON.toJSONString(frame));

        assertThat(json.getString("status")).isEqualTo("succeeded");
        assertThat(json.getLong("durationMs")).isEqualTo(42L);
        assertThat(json.containsKey("error")).isFalse();
    }

    @Test
    void failedFrame_hasDurationMsAndError() {
        ToolEventFrame frame = ToolEventFrame.from(ToolEvent.terminal(
                "req-1|demo_script_tool|def456", "demo_script_tool", "{\"minutes\":15}",
                false, 7L, "工具执行超时（3000ms）"));

        JSONObject json = JSON.parseObject(JSON.toJSONString(frame));

        assertThat(json.getString("status")).isEqualTo("failed");
        assertThat(json.getLong("durationMs")).isEqualTo(7L);
        assertThat(json.getString("error")).isEqualTo("工具执行超时（3000ms）");
    }

    @Test
    void serializedJson_isSingleLine_noNewlineEscapes() {
        // SSE data: 行必须单行；载荷内不得出现裸换行/反斜杠 n 转义（arguments 摘要预先脱敏截断为单行）
        ToolEventFrame frame = ToolEventFrame.from(ToolEvent.started(
                "req-1|t|a", "t", "plain-one-line-args"));

        String json = JSON.toJSONString(frame);

        assertThat(json).doesNotContain("\n").doesNotContain("\\n");
    }
}
