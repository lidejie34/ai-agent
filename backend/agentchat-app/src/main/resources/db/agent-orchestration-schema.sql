-- 迭代5：编排跑次审计表（MySQL 8，utf8mb4）。OrchestrationSchemaInitializer 持 DataSource
-- 经 ScriptUtils 懒执行（首次编排路径触发；CREATE TABLE IF NOT EXISTS 幂等）；无外键。
-- run_id = 工具挂载 requestId（关联 agent_tool_call_log.call_id 前缀）；无工具时为编排自生成 UUID。
CREATE TABLE IF NOT EXISTS agent_orchestration_run (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  run_id        VARCHAR(64)  NOT NULL COMMENT '整轮请求 ID（= 工具挂载 requestId，关联 agent_tool_call_log.call_id 前缀）',
  session_id    VARCHAR(36)  NULL COMMENT '主会话 ID；无状态为 NULL',
  round         INT          NOT NULL DEFAULT 0 COMMENT 'Planner 轮次（路由=1；EXECUTOR/SYNTH 为发生时轮次）',
  role          VARCHAR(16)  NOT NULL COMMENT 'PLANNER / EXECUTOR / SYNTH',
  task_id       VARCHAR(64)  NULL COMMENT '子任务 ID（PLANNER/SYNTH 为 NULL）',
  task_title    VARCHAR(255) NULL COMMENT '子任务标题快照',
  status        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED / TIMEOUT / SKIPPED',
  duration_ms   BIGINT       NOT NULL DEFAULT 0,
  model         VARCHAR(128) NULL COMMENT '本次实际模型 ID',
  error_message VARCHAR(1000) NULL COMMENT '失败原因：脱敏截断，无堆栈无密钥',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_run     (run_id),
  INDEX idx_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='编排跑次审计表';
