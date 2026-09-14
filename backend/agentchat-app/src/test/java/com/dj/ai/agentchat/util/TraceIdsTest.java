package com.dj.ai.agentchat.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代9：{@link TraceIds} 形态与 MDC 读写分支。
 */
class TraceIdsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void newTraceId_is16LowerHex() {
        String id = TraceIds.newTraceId();
        assertThat(id).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(TraceIds.newTraceId()).isNotEqualTo(id);
    }

    @Test
    void current_returnsNullWhenMdcEmpty() {
        assertThat(TraceIds.current()).isNull();
    }

    @Test
    void currentOrNew_returnsMdcValueWhenPresent() {
        MDC.put(TraceIds.MDC_KEY, "abcd1234abcd1234");
        assertThat(TraceIds.current()).isEqualTo("abcd1234abcd1234");
        assertThat(TraceIds.currentOrNew()).isEqualTo("abcd1234abcd1234");
    }

    @Test
    void currentOrNew_fallsBackToNewIdWhenMdcEmpty() {
        String id = TraceIds.currentOrNew();
        assertThat(id).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void currentOrNew_fallsBackAfterMdcCleared() {
        MDC.put(TraceIds.MDC_KEY, "abcd1234abcd1234");
        MDC.remove(TraceIds.MDC_KEY);
        assertThat(TraceIds.current()).isNull();
        String id = TraceIds.currentOrNew();
        assertThat(id).hasSize(16).isNotEqualTo("abcd1234abcd1234");
    }
}
