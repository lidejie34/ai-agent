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
  role       VARCHAR(16) NOT NULL COMMENT 'user / assistant（system 本期不入库）',
  content    MEDIUMTEXT NOT NULL COMMENT '消息全文，不截断',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session (session_id, id)
);
