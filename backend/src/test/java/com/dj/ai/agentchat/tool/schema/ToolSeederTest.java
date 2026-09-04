package com.dj.ai.agentchat.tool.schema;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1：种子器幂等语义（AC-7/8/9）——缺行才插、已存在不覆盖、SCRIPT 种子默认禁用、
 * guide 取自 classpath skills/、guide 资源缺失不阻断。
 */
class ToolSeederTest {

    private final AgentToolMapper mapper = mock(AgentToolMapper.class);
    private final ToolSeeder seeder = new ToolSeeder(mapper);

    @Test
    void seedIfAbsent_missingRows_insertsBuiltinEnabledAndScriptDisabled() {
        when(mapper.selectCount(any())).thenReturn(0L);

        seeder.seedIfAbsent();

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(mapper, times(2)).insert(captor.capture());
        AgentToolPO builtin = captor.getAllValues().get(0);
        AgentToolPO script = captor.getAllValues().get(1);

        // BUILTIN 行
        assertThat(builtin.getToolName()).isEqualTo("analyze_log_errors");
        assertThat(builtin.getHandlerType()).isEqualTo("BUILTIN");
        assertThat(builtin.getEnabled()).isTrue();
        assertThat(builtin.getTimeoutMs()).isEqualTo(30000);
        assertThat(builtin.getOutputMaxChars()).isEqualTo(8000);
        JSONObject builtinConfig = JSON.parseObject(builtin.getHandlerConfig());
        assertThat(builtinConfig.getString("bean")).isEqualTo("analyzeLogErrors");
        // input_schema 是合法 JSON 且声明 minutes 必填
        JSONObject builtinSchema = JSON.parseObject(builtin.getInputSchema());
        assertThat(builtinSchema.getJSONArray("required")).contains("minutes");
        assertThat(builtinSchema.getJSONObject("properties").getJSONObject("minutes")
                .getIntValue("maximum")).isEqualTo(1440);
        // guide 取自 classpath，全文非空
        assertThat(builtin.getGuideMd()).isNotBlank().contains("analyze_log_errors");

        // SCRIPT 行：默认禁用（D4）
        assertThat(script.getToolName()).isEqualTo("log_error_count");
        assertThat(script.getHandlerType()).isEqualTo("SCRIPT");
        assertThat(script.getEnabled()).isFalse();
        assertThat(script.getOutputMaxChars()).isEqualTo(4000);
        JSONObject scriptConfig = JSON.parseObject(script.getHandlerConfig());
        assertThat(scriptConfig.getString("script")).isEqualTo("log_error_count.sh");
        assertThat(script.getGuideMd()).isNotBlank();
    }

    @Test
    void seedIfAbsent_rowsAlreadyExist_doesNotInsert_orOverwrite() {
        when(mapper.selectCount(any())).thenReturn(1L);

        seeder.seedIfAbsent();

        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void seedIfAbsent_builtinExists_insertsOnlyScript() {
        // 第一次 selectCount（builtin）=1 已存在；第二次（script）=0 缺失
        when(mapper.selectCount(any())).thenReturn(1L).thenReturn(0L);

        seeder.seedIfAbsent();

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(mapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getToolName()).isEqualTo("log_error_count");
    }

    @Test
    void loadGuide_missingResource_returnsNullWithoutThrowing() {
        assertThat(ToolSeeder.loadGuide("skills/does-not-exist-xyz.md")).isNull();
    }

    @Test
    void seedIfAbsent_dbException_propagatesToCaller() {
        when(mapper.selectCount(any())).thenThrow(new RuntimeException("connection refused"));
        // 异常上抛由调用方 best-effort 吞掉（Runner/懒装载），seeder 自身不吞
        assertThatCode(() -> seeder.seedIfAbsent()).isInstanceOf(RuntimeException.class);
    }
}
