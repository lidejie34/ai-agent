package com.dj.ai.agentchat.rag.admin.dto;

import com.dj.ai.agentchat.rag.store.RagDocument;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 知识库文档管理视图（迭代6）：不含原文/哈希（列表与上传/重建响应共用）；
 * 时间序列化为 {@code yyyy-MM-dd HH:mm:ss} 字符串，与既有 admin 接口线格式一致。
 */
public record KbDocumentView(Long id,
                             String fileName,
                             Integer sizeBytes,
                             Integer chunkCount,
                             String status,
                             String error,
                             String createdAt,
                             String updatedAt) {

    private static final DateTimeFormatter DATE_TIME_SPACE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static KbDocumentView from(RagDocument doc) {
        return new KbDocumentView(
                doc.id(),
                doc.fileName(),
                doc.sizeBytes(),
                doc.chunkCount(),
                doc.status(),
                doc.error(),
                format(doc.createdAt()),
                format(doc.updatedAt()));
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.withNano(0).format(DATE_TIME_SPACE);
    }
}
