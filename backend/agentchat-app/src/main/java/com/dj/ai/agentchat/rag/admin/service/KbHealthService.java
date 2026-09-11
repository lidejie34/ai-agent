package com.dj.ai.agentchat.rag.admin.service;

import com.dj.ai.agentchat.rag.admin.dto.KbHealthView;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库健康检查（迭代6）：Ollama bge-m3 轻探活（一次最短 embedding）+
 * PG/pgvector 计数探测（rag_document/rag_chunk 可查即视为表与扩展就绪）。
 * 分项独立 try/catch——任一下游挂掉只在视图里反映为 false，不抛异常。
 */
@Slf4j
public class KbHealthService {

    /** bge-m3 向量维度（pgvector 列 vector(1024) 同源）。 */
    public static final int EMBEDDING_DIMENSIONS = 1024;

    private final RagEmbeddingService embeddingService;
    private final KbRepository repository;

    public KbHealthService(RagEmbeddingService embeddingService, KbRepository repository) {
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    public KbHealthView health() {
        boolean ollamaOk;
        try {
            ollamaOk = embeddingService.ping();
        } catch (RuntimeException e) {
            log.warn("RAG Ollama 健康探测异常: {}", e.getMessage());
            ollamaOk = false;
        }

        boolean pgOk;
        long documentCount = 0L;
        long chunkCount = 0L;
        try {
            documentCount = repository.countDocuments();
            chunkCount = repository.countChunks();
            pgOk = true;
        } catch (RuntimeException e) {
            log.warn("RAG PG/pgvector 健康探测失败: {}", e.getMessage());
            pgOk = false;
            // 任一计数失败都视为 PG 探测未完成：不得带出可能已赋的半截计数
            documentCount = 0L;
            chunkCount = 0L;
        }
        return new KbHealthView(true, ollamaOk, pgOk, documentCount, chunkCount, EMBEDDING_DIMENSIONS);
    }
}
