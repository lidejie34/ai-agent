package com.dj.ai.agentchat.orchestration.audit;

import com.dj.ai.agentchat.orchestration.audit.mapper.OrchestrationRunMapper;
import com.dj.ai.agentchat.orchestration.audit.po.OrchestrationRunPO;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;

/**
 * 编排跑次审计落库（迭代5，T1，AC-15/AC-51/AC-60.2）：best-effort 写入
 * {@code agent_orchestration_run}。
 *
 * <p>任何 DB/建表异常都不影响编排与对话：{@link OrchestrationSchemaInitializer}
 * 建表失败仅 warn 且不插入；插入 {@link DataAccessException} 仅 error 日志。
 * {@code errorMessage} 经可选 {@link SecretRedactor} 脱敏（工具开关关闭、bean 缺席时
 * 仅长度截断兜底）并截断 ≤1000 字，无堆栈无密钥（AC-36）。
 */
@Slf4j
public class OrchestrationAuditService {

    public static final String ROLE_PLANNER = "PLANNER";
    public static final String ROLE_EXECUTOR = "EXECUTOR";
    public static final String ROLE_SYNTH = "SYNTH";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 审计 error_message 最大字符数（帧 error ≤500，审计放宽到 1000）。 */
    static final int MAX_ERROR_CHARS = 1000;

    private final OrchestrationRunMapper mapper;
    private final OrchestrationSchemaInitializer schemaInitializer;
    /** 可空：工具开关关闭时无 SecretRedactor bean → 仅长度截断兜底（AC-36）。 */
    @Nullable
    private final SecretRedactor redactor;

    public OrchestrationAuditService(OrchestrationRunMapper mapper,
                                     OrchestrationSchemaInitializer schemaInitializer,
                                     @Nullable SecretRedactor redactor) {
        this.mapper = mapper;
        this.schemaInitializer = schemaInitializer;
        this.redactor = redactor;
    }

    /**
     * best-effort 记录一条编排跑次审计行；任何异常仅日志，绝不抛出（AC-51）。
     */
    public void record(String runId, @Nullable String sessionId, int round, String role,
                       @Nullable String taskId, @Nullable String taskTitle, String status,
                       long durationMs, @Nullable String model, @Nullable String errorMessage) {
        try {
            schemaInitializer.ensureSchema();
        } catch (RuntimeException e) {
            log.warn("编排审计建表失败（跳过本次写入，不影响编排）: runId={}, 原因={}",
                    runId, e.getMessage());
            return;
        }
        OrchestrationRunPO po = new OrchestrationRunPO();
        po.setRunId(runId);
        po.setSessionId(sessionId);
        po.setRound(round);
        po.setRole(role);
        po.setTaskId(taskId);
        po.setTaskTitle(taskTitle);
        po.setStatus(status);
        po.setDurationMs(durationMs);
        po.setModel(model);
        po.setErrorMessage(sanitizeError(errorMessage));
        try {
            mapper.insert(po);
        } catch (DataAccessException e) {
            // 审计失败绝不影响编排（AC-51）
            log.error("编排审计落库失败（不影响编排）: runId={}, role={}, round={}, 原因={}",
                    runId, role, round, e.getMessage());
        }
    }

    /** 脱敏（redactor 缺席时跳过）+ 截断 ≤1000 字；null 透传。 */
    @Nullable
    private String sanitizeError(@Nullable String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String text = redactor == null ? errorMessage : redactor.redact(errorMessage);
        if (text != null && text.length() > MAX_ERROR_CHARS) {
            text = text.substring(0, MAX_ERROR_CHARS);
        }
        return text;
    }
}
