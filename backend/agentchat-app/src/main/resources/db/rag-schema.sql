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
