package com.dj.ai.agentchat.dim;

import java.time.LocalDateTime;

/**
 * 受管项目视图（迭代10；自 rag.dim 迁至 MySQL 侧独立 dim 包）：dim_project 表行 +
 * 引用文档数（docCount 由服务层合并 PG rag_document 分组计数，非表列）。
 */
public record DimProject(
        Long id,
        String name,
        String remark,
        long docCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 替换 docCount 生成新视图（服务层合并引用计数用）。 */
    public DimProject withDocCount(long count) {
        return new DimProject(id, name, remark, count, createdAt, updatedAt);
    }
}
