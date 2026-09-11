package com.dj.ai.agentchat.rag.store;

/**
 * 检索命中视图：来源文件名（回答引用用）+ 片段原文 + 余弦相似度（1 - cosine distance）。
 */
public record RagChunkView(String fileName, String content, double score) {
}
