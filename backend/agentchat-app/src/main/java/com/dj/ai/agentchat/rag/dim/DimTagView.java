package com.dj.ai.agentchat.rag.dim;

/**
 * 维度标签视图（迭代10 追加·维度维护）：标签名 + 引用文档数。
 * 标签无独立表——来源是 rag_document.tags JSONB 数组的 distinct 展开。
 */
public record DimTagView(String name, long docCount) {
}
