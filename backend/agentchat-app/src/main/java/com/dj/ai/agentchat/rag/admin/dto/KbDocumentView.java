package com.dj.ai.agentchat.rag.admin.dto;

import com.dj.ai.agentchat.rag.store.RagDocument;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 知识库文档管理视图（迭代6）：不含原文/哈希（列表与上传/重建响应共用）；
 * 时间序列化为 {@code yyyy-MM-dd HH:mm:ss} 字符串，与既有 admin 接口线格式一致。
 *
 * <p>迭代10 additive 扩展：{@code project}（可空）、{@code tags}（非空列表）——
 * 线格式向后兼容（客户端忽略未知字段）。
 */
public record KbDocumentView(Long id,
                             String fileName,
                             Integer sizeBytes,
                             Integer chunkCount,
                             String status,
                             String error,
                             String createdAt,
                             String updatedAt,
                             String project,
                             List<String> tags) {

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
                format(doc.updatedAt()),
                doc.project(),
                doc.tags() == null ? List.of() : doc.tags());
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.withNano(0).format(DATE_TIME_SPACE);
    }
}
