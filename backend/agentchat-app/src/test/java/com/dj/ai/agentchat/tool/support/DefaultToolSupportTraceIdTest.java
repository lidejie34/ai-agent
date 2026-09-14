package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import com.dj.ai.agentchat.util.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代9（决策 b）：{@link DefaultToolSupport} 的 requestId 来源——
 * MDC 有 traceId（开启态请求线程）时 mount 的 toolContext.requestId==traceId
 * （call_id 前缀、编排 run_id 同源，一个 ID grep 全链）；
 * 无 MDC（关闭态）时回退新生成 ID（与迭代8 的 UUID 同为无格式假设唯一串）。
 */
class DefaultToolSupportTraceIdTest {

    private final ToolRegistry registry = mock(ToolRegistry.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mountWithMdcTraceId_requestIdEqualsTraceId() {
        when(registry.toolCallbacks()).thenReturn(List.of(mock(ToolCallback.class)));
        MDC.put(TraceIds.MDC_KEY, "abcd1234abcd1234");

        ToolMount mount = new DefaultToolSupport(registry).mountTools("sess-1");

        assertThat(mount).isNotNull();
        assertThat(mount.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID))
                .isEqualTo("abcd1234abcd1234");
    }

    @Test
    void mountWithoutMdc_requestIdIsFreshlyGenerated_andDistinctAcrossMounts() {
        when(registry.toolCallbacks()).thenReturn(List.of(mock(ToolCallback.class)));
        DefaultToolSupport support = new DefaultToolSupport(registry);

        ToolMount first = support.mountTools(null);
        ToolMount second = support.mountTools(null);

        Object firstId = first.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID);
        Object secondId = second.toolContext().get(DefaultToolSupport.CTX_REQUEST_ID);
        assertThat(firstId).isInstanceOf(String.class);
        assertThat(secondId).isInstanceOf(String.class);
        assertThat(secondId).isNotEqualTo(firstId);
    }
}
