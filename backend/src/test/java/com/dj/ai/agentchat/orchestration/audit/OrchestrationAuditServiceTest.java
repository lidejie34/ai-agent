package com.dj.ai.agentchat.orchestration.audit;

import com.dj.ai.agentchat.orchestration.audit.mapper.OrchestrationRunMapper;
import com.dj.ai.agentchat.orchestration.audit.po.OrchestrationRunPO;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1：编排跑次审计 best-effort 落库（AC-15/AC-36/AC-51/AC-60.2）——
 * record 先懒建表再插入；建表/插入任何异常仅日志不抛出；
 * SKIPPED/TIMEOUT 状态字段落库；error 脱敏 + 截断 ≤1000 字。
 */
class OrchestrationAuditServiceTest {

    private OrchestrationRunMapper mapper;
    private OrchestrationSchemaInitializer initializer;
    private OrchestrationAuditService audit;

    @BeforeEach
    void setUp() {
        mapper = mock(OrchestrationRunMapper.class);
        initializer = mock(OrchestrationSchemaInitializer.class);
        audit = new OrchestrationAuditService(mapper, initializer, null);
    }

    @Test
    void record_plannerRow_insertsAllFields() {
        audit.record("run-1", "sid-1", 1, "PLANNER", null, null,
                "SUCCESS", 820L, "planner-model", null);

        verify(initializer).ensureSchema();
        ArgumentCaptor<OrchestrationRunPO> captor = ArgumentCaptor.forClass(OrchestrationRunPO.class);
        verify(mapper).insert(captor.capture());
        OrchestrationRunPO po = captor.getValue();
        assertThat(po.getRunId()).isEqualTo("run-1");
        assertThat(po.getSessionId()).isEqualTo("sid-1");
        assertThat(po.getRound()).isEqualTo(1);
        assertThat(po.getRole()).isEqualTo("PLANNER");
        assertThat(po.getTaskId()).isNull();
        assertThat(po.getTaskTitle()).isNull();
        assertThat(po.getStatus()).isEqualTo("SUCCESS");
        assertThat(po.getDurationMs()).isEqualTo(820L);
        assertThat(po.getModel()).isEqualTo("planner-model");
        assertThat(po.getErrorMessage()).isNull();
    }

    @Test
    void record_executorRow_keepsTaskFields() {
        audit.record("run-1", "sid-1", 2, "EXECUTOR", "t3", "汇总排查建议",
                "FAILED", 1200L, "executor-model", "工具调用失败");

        ArgumentCaptor<OrchestrationRunPO> captor = ArgumentCaptor.forClass(OrchestrationRunPO.class);
        verify(mapper).insert(captor.capture());
        OrchestrationRunPO po = captor.getValue();
        assertThat(po.getRole()).isEqualTo("EXECUTOR");
        assertThat(po.getRound()).isEqualTo(2);
        assertThat(po.getTaskId()).isEqualTo("t3");
        assertThat(po.getTaskTitle()).isEqualTo("汇总排查建议");
        assertThat(po.getStatus()).isEqualTo("FAILED");
        assertThat(po.getErrorMessage()).isEqualTo("工具调用失败");
    }

    @Test
    void record_skippedAndTimeout_statusesPersist() {
        audit.record("run-1", null, 3, "EXECUTOR", "t9", "被跳过的任务",
                "SKIPPED", 0L, null, null);
        audit.record("run-1", null, 3, "EXECUTOR", "t8", "超时任务",
                "TIMEOUT", 60000L, "executor-model", "子任务执行超时");

        verify(mapper, org.mockito.Mockito.times(2)).insert(any(OrchestrationRunPO.class));
    }

    @Test
    void record_nullSessionId_persistsNullSession() {
        audit.record("run-uuid", null, 1, "SYNTH", null, null,
                "SUCCESS", 500L, "planner-model", null);

        ArgumentCaptor<OrchestrationRunPO> captor = ArgumentCaptor.forClass(OrchestrationRunPO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isNull();
    }

    @Test
    void record_schemaFailure_isSwallowed_andNoInsert() {
        org.mockito.Mockito.doThrow(new CannotGetJdbcConnectionRuntime("DB down"))
                .when(initializer).ensureSchema();

        assertThatCode(() -> audit.record("run-1", "sid-1", 1, "PLANNER", null, null,
                "FAILED", 10L, null, "boom")).doesNotThrowAnyException();

        verify(mapper, never()).insert(any());
    }

    @Test
    void record_insertFailure_isSwallowed() {
        when(mapper.insert(any())).thenThrow(new DataAccessResourceFailureException("insert failed"));

        assertThatCode(() -> audit.record("run-1", "sid-1", 1, "PLANNER", null, null,
                "SUCCESS", 10L, null, null)).doesNotThrowAnyException();
    }

    @Test
    void record_errorMessage_isRedactedAndTruncatedTo1000() {
        SecretRedactor redactor = new SecretRedactor(java.util.List.of());
        OrchestrationAuditService redacting = new OrchestrationAuditService(mapper, initializer, redactor);
        String longError = "密钥泄露 ark-abcdefgh1234567890 " + "x".repeat(2000);

        redacting.record("run-1", "sid-1", 2, "EXECUTOR", "t1", "任务",
                "FAILED", 10L, null, longError);

        ArgumentCaptor<OrchestrationRunPO> captor = ArgumentCaptor.forClass(OrchestrationRunPO.class);
        verify(mapper).insert(captor.capture());
        String error = captor.getValue().getErrorMessage();
        assertThat(error).hasSizeLessThanOrEqualTo(1000);
        assertThat(error).doesNotContain("ark-abcdefgh1234567890");
        assertThat(error).contains(SecretRedactor.REDACTED);
    }

    /** 运行时异常替身（SchemaInitializer 抛 CannotGetJdbcConnectionException 体系）。 */
    private static class CannotGetJdbcConnectionRuntime extends RuntimeException {
        CannotGetJdbcConnectionRuntime(String message) {
            super(message);
        }
    }
}
