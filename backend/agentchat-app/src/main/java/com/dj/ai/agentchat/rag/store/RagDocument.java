package com.dj.ai.agentchat.rag.store;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 文档行（rag_document）。{@code content} 存原文以支撑重建索引（F5）；
 * {@code status} 取值 {@link #STATUS_READY} / {@link #STATUS_FAILED}（同步处理，无 PROCESSING 中间态）。
 *
 * <p>迭代10 维度元数据：{@code project} 项目归属（单值，可空=未打标）、
 * {@code tags} 标签集合（多值，非空，空=List.of()）；检索/列表过滤在 doc 维 prefilter。
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
        LocalDateTime updatedAt,
        String project,
        List<String> tags) {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * 迭代6 兼容构造：无维度元数据（project=null、tags=空，与存量行语义一致）。
     */
    public RagDocument(Long id, String fileName, int sizeBytes, String content, String contentHash,
                       int chunkCount, String status, String error,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, fileName, sizeBytes, content, contentHash, chunkCount, status, error,
                createdAt, updatedAt, null, List.of());
    }

    /** 列表视图（不含原文大字段）。 */
    public RagDocument withoutContent() {
        return new RagDocument(id, fileName, sizeBytes, null, contentHash, chunkCount,
                status, error, createdAt, updatedAt, project, tags);
    }
}
