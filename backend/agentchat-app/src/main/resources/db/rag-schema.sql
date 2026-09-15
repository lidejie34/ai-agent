-- 迭代6 RAG 知识库 schema（PG ai_vector 库 + pgvector 扩展）
-- 幂等：首次懒建表/启动 runner 执行均可重复运行；失败不置位、DB 恢复后重试。

-- pgvector 扩展（容器已装 vector 0.8.6 可用文件，此处 CREATE 启用）
CREATE EXTENSION IF NOT EXISTS vector;

-- 文档表：原文落库以支持 reindex；file_name 唯一承载同名覆盖语义
CREATE TABLE IF NOT EXISTS rag_document (
    id           BIGSERIAL PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL UNIQUE,
    size_bytes   INT NOT NULL,
    content      TEXT NOT NULL,
    content_hash CHAR(40) NOT NULL,
    chunk_count  INT NOT NULL DEFAULT 0,
    status       VARCHAR(16) NOT NULL,
    error        VARCHAR(1000) NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- 片段表：file_name 冗余，检索免 join 拿来源；embedding 固定 1024 维（bge-m3）
CREATE TABLE IF NOT EXISTS rag_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1024) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_doc ON rag_chunk (doc_id);

-- 余弦距离 HNSW 索引（检索走 <=> 操作符）
CREATE INDEX IF NOT EXISTS idx_rag_chunk_emb ON rag_chunk
    USING hnsw (embedding vector_cosine_ops);

-- 迭代10 知识库维度元数据：project 单值归属 + tags 多值标签（幂等迁移，
-- 存量行 project=NULL / tags=[]，与迭代6 行为一致；检索过滤在 doc 维 prefilter）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS project VARCHAR(64) NULL;
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]';

CREATE INDEX IF NOT EXISTS idx_rag_doc_project ON rag_document (project);
CREATE INDEX IF NOT EXISTS idx_rag_doc_tags ON rag_document USING gin (tags);

-- 迭代10 追加：维度项目受管表——项目必须先在此维护，上传/编辑/过滤/聊天全部引用
-- 既有值（rag_document.project 按名称字符串松耦合，不改外键，改名由服务层联动更新）。
-- 命名 dim_ 前缀（非 kb_）：后续工具调度（MySQL 侧）挂项目/角色时复用同一份数据。
CREATE TABLE IF NOT EXISTS dim_project (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE,
    remark     VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
