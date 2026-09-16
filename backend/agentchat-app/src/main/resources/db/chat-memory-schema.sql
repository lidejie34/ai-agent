-- 迭代3：会话记忆表结构（FR-15，MySQL 8 方言；已经 13306 实测）。
-- 由 ChatMemorySchemaInitializer 持 DataSource 经 spring-jdbc ScriptUtils 执行：
-- 懒建表（首次记忆路径）+ 启动期 best-effort；CREATE TABLE IF NOT EXISTS 保证重复执行幂等。

CREATE TABLE IF NOT EXISTS chat_session (
  session_id  VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '服务端生成的会话 UUID',
  title       VARCHAR(200) NULL COMMENT '会话标题（本期恒 NULL，列预留）',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL COMMENT '所属会话',
  role       VARCHAR(16) NOT NULL COMMENT 'user / assistant / system；迭代8 起另有 tool_evidence（工具查证证据，仅供模型回放，用户出口白名单过滤）',
  content    MEDIUMTEXT NOT NULL COMMENT '消息全文，不截断',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session (session_id, id)
);

-- 迭代12：会话级范围配置（知识库维度 + 工具/MCP 选择），与 chat_session 1:1，
-- 会话删除时经应用层级联删（MybatisSessionManager.deleteCascade 同事务）。
-- 四列统一三态：NULL=默认全部；'[]'=显式全不选；非空 JSON 数组=子集。
CREATE TABLE IF NOT EXISTS chat_session_scope (
  session_id  VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '所属会话（1:1）',
  kb_projects VARCHAR(1000) NULL COMMENT 'JSON 数组；NULL=全部项目（默认）',
  kb_tags     VARCHAR(1000) NULL COMMENT 'JSON 数组；NULL=全部标签（默认）',
  tool_names  TEXT NULL COMMENT 'JSON 数组；NULL=全部 DB 工具（默认）',
  mcp_servers VARCHAR(1000) NULL COMMENT 'JSON 数组；NULL=全部 READY MCP server（默认）',
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
