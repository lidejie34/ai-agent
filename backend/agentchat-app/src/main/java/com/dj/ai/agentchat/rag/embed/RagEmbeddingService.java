package com.dj.ai.agentchat.rag.embed;

import com.dj.ai.agentchat.rag.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG Embedding 服务（迭代6）：封装本机 Ollama（OpenAI 兼容 /v1/embeddings, bge-m3）。
 *
 * <p>批量语义：按 {@link #BATCH_SIZE} 分批，每批走<b>一次</b> HTTP 请求
 * （{@code EmbeddingModel.embed(List)} 默认实现直发 input 数组，实测 16 条约 300ms）；
 * 输出顺序严格对齐入参顺序。任何底层异常统一包装为 {@link RagEmbeddingException}：
 * 网络 IO/超时（{@link ResourceAccessException}）消息带「超时/连接」便于上层分类与日志过滤。
 */
@Slf4j
public class RagEmbeddingService {

    /** 单批文本上限：bge-m3 8192 token 容量充裕，16 为实测时延与请求体的平衡值。 */
    static final int BATCH_SIZE = 16;

    private final EmbeddingModel embeddingModel;
    private final String model;

    public RagEmbeddingService(EmbeddingModel embeddingModel, RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.model = properties.getOllama().getModel();
    }

    /** 单条 query 向量化（Advisor 检索路径）。 */
    public float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    /**
     * 批量向量化（上传切片路径）：分批单请求，结果顺序与入参一致；空入参返回空列表。
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            try {
                List<float[]> vectors = embeddingModel.embed(batch);
                if (vectors == null || vectors.size() != batch.size()) {
                    throw new RagEmbeddingException(
                            "Embedding 返回数量异常: 请求 " + batch.size() + " 条，实际 "
                                    + (vectors == null ? 0 : vectors.size()) + " 条（model=" + model + "）", null);
                }
                result.addAll(vectors);
            } catch (RagEmbeddingException e) {
                throw e;
            } catch (Exception e) {
                throw wrap(e);
            }
        }
        return result;
    }

    /** 轻探活：一次最短 embedding；任何失败返回 false（不抛异常，供健康检查使用）。 */
    public boolean ping() {
        try {
            embed("ping");
            return true;
        } catch (RuntimeException e) {
            log.warn("RAG embedding 探活失败（model={}）: {}", model, e.getMessage());
            return false;
        }
    }

    private RagEmbeddingException wrap(Exception e) {
        String hint;
        if (e instanceof ResourceAccessException) {
            hint = "Ollama embedding 连接/超时失败";
        } else {
            hint = "Ollama embedding 调用失败";
        }
        log.warn("{}（model={}）: {}", hint, model, e.getMessage());
        return new RagEmbeddingException(hint + ": " + e.getMessage() + "（model=" + model + "）", e);
    }
}
