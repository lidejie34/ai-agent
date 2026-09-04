package com.dj.ai.agentchat.tool.audit;

import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4：审计 best-effort（AC-38）——正常插入；DuplicateKey（call_id 幂等兜底）吞掉 debug；
 * 其余 DataAccessException 仅 log.error，均不影响对话。
 */
class ToolAuditServiceTest {

    private final AgentToolCallLogMapper mapper = mock(AgentToolCallLogMapper.class);
    private final ToolAuditService service = new ToolAuditService(mapper);

    private AgentToolCallLogPO samplePo() {
        AgentToolCallLogPO po = new AgentToolCallLogPO();
        po.setCallId("req-1|analyze_log_errors|abc");
        po.setToolName("analyze_log_errors");
        po.setHandlerType("BUILTIN");
        po.setStatus("SUCCESS");
        po.setDurationMs(123L);
        po.setResultChars(456);
        return po;
    }

    @Test
    void record_normal_inserts() {
        service.record(samplePo());
        verify(mapper).insert(any(AgentToolCallLogPO.class));
    }

    @Test
    void record_duplicateKey_isSwallowed() {
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("uk_call_id 冲突"));
        assertThatCode(() -> service.record(samplePo())).doesNotThrowAnyException();
    }

    @Test
    void record_dataAccessException_isSwallowed() {
        when(mapper.insert(any())).thenThrow(new QueryTimeoutException("查询超时"));
        assertThatCode(() -> service.record(samplePo())).doesNotThrowAnyException();
    }
}
