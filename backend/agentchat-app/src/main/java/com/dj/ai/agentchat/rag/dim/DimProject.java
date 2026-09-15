package com.dj.ai.agentchat.rag.dim;

import java.time.LocalDateTime;

/**
 * 维度项目视图（迭代10 追加·维度维护）：dim_project 行 + 引用文档数。
 * docCount 由列表 SQL 子查询带出，单实体查询场景可为 0。
 */
public record DimProject(long id, String name, String remark, long docCount,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
}
