package com.dj.ai.agentchat.rag.store;

import java.time.LocalDateTime;

/**
 * RAG 文档行（rag_document）。{@code content} 存原文以支撑重建索引（F5）；
 * {@code status} 取值 {@link #STATUS_READY} / {@link #STATUS_FAILED}（同步处理，无 PROCESSING 中间态）。
 */
public record RagDocument(
        Long id,
        String fileName,
        int sizeBytes,
        String content,
        String contentHash,
        int chunkCount,
        String status,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    /** 列表视图（不含原文大字段）。 */
    public RagDocument withoutContent() {
        return new RagDocument(id, fileName, sizeBytes, null, contentHash, chunkCount,
                status, error, createdAt, updatedAt);
    }
}
