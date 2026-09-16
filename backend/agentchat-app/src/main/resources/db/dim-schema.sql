-- 迭代10 迁移：维度项目受管表（MySQL 8 方言）——项目必须先在此维护，
-- 知识库文档（rag_document.project，PG 侧）与后续工具调度（MySQL 侧）均按 name
-- 字符串松耦合引用，不建跨库外键；改名由服务层联动更新文档归属。
-- 由 DimProjectSchemaInitializer 持主 DataSource 经 spring-jdbc ScriptUtils 执行：
-- 懒建表（首次维度路径）+ 启动期 best-effort；CREATE TABLE IF NOT EXISTS 幂等。
-- 注意：dim_project 早期曾建于 rag PG 库（rag-schema.sql），后为「RAG 关闭也能
-- 维护项目/供工具调度复用」迁至 MySQL 主库；PG 侧残留表可手工 DROP TABLE dim_project。

CREATE TABLE IF NOT EXISTS dim_project (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(64) NOT NULL COMMENT '项目名（全局唯一，中文/字母/数字/中划线/下划线）',
  remark     VARCHAR(255) NULL COMMENT '备注',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dim_project_name (name)
);
