-- 插入迭代 G：工具注册表 + 工具调用审计表（MySQL 8 方言，utf8mb4）。
-- 由 ToolSchemaInitializer 持 DataSource 经 spring-jdbc ScriptUtils 执行：
-- 懒建表（首次工具装载/管理端路径）+ 启动期 best-effort；CREATE TABLE IF NOT EXISTS 幂等。
-- 时区约定同 chat-memory-schema.sql：依赖 JDBC 连接 connectionTimeZone=Asia/Shanghai
-- &forceConnectionTimeZoneToSession=true（application.yml 已配，必须保留）。
-- JSON 列：PO 以 String 持有 + fastjson2 代码内解析，不写 TypeHandler（S3）。

CREATE TABLE IF NOT EXISTS agent_tool (
  id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tool_name        VARCHAR(64)   NOT NULL COMMENT '工具名（function calling name），^[a-z][a-z0-9_]{1,63}$',
  description      VARCHAR(2000) NOT NULL COMMENT '工具描述（模型据此判断调用时机）',
  input_schema     JSON          NOT NULL COMMENT '入参 JSON Schema（逐字成为 ToolDefinition.inputSchema）',
  handler_type     VARCHAR(16)   NOT NULL COMMENT 'BUILTIN / SCRIPT（HTTP / SCRIPT_DB 预留不挂载）',
  handler_config   JSON          NOT NULL COMMENT '处理器配置：BUILTIN {"bean":"..."}；SCRIPT {"script":"文件名"}',
  guide_md         MEDIUMTEXT    NULL COMMENT 'SKILL.md 式操作指南全文（种子取 classpath skills/）',
  enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用挂载',
  timeout_ms       INT           NOT NULL DEFAULT 30000 COMMENT '单次执行超时毫秒（硬上限 60000）',
  output_max_chars INT           NOT NULL DEFAULT 8000 COMMENT '回传模型结果最大字符数',
  created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具注册表';

CREATE TABLE IF NOT EXISTS agent_tool_call_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  call_id       VARCHAR(160) NOT NULL COMMENT '幂等键：requestId|toolName|sha1(入参JSON)',
  -- 迭代4：MCP 工具暴露名 <server>_<tool>（规整后 ≤64）但审计存完整全名，列宽放宽至 128；
  -- 既有库由 AuditColumnWidthMigration best-effort ALTER（information_schema 查宽 <128 才改）。
  tool_name     VARCHAR(128) NOT NULL COMMENT '工具名字符串留存（MCP 为带前缀全名；工具行删除后审计保留，无外键）',
  handler_type  VARCHAR(16)  NOT NULL COMMENT '处理器类型快照',
  session_id    VARCHAR(36)  NULL COMMENT '会话 ID；无状态对话为 NULL',
  input_summary VARCHAR(2000) NOT NULL COMMENT '入参 JSON：脱敏 + 截断',
  status        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED / TIMEOUT',
  duration_ms   BIGINT       NOT NULL DEFAULT 0,
  error_message VARCHAR(1000) NULL COMMENT '失败/超时原因：脱敏截断，无堆栈无密钥',
  result_chars  INT          NOT NULL DEFAULT 0 COMMENT '实际回传模型的结果字符数（截断+guide 后）',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_call_id (call_id),
  INDEX idx_tool_name (tool_name, created_at),
  INDEX idx_session  (session_id, created_at),
  INDEX idx_status   (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具调用审计表';
