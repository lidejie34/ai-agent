package com.dj.ai.agentchat.rag.embed;

/**
 * RAG embedding 调用失败（Ollama 不可达/超时/HTTP 错误/返回异常）。
 *
 * <p>调用方语义：上传链路转 KB_EMBEDDING_FAILED（文档落 FAILED）；
 * Advisor 链路全量 catch 后降级放行（不带检索片段，对话不中断）。
 */
public class RagEmbeddingException extends RuntimeException {

    public RagEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
