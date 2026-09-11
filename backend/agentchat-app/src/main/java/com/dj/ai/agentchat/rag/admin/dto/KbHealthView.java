package com.dj.ai.agentchat.rag.admin.dto;

/**
 * 知识库健康视图（迭代6）：enabled 恒定 true（控制器在 RAG 开关开时可达）；
 * ollamaOk/pgOk 分项降级；dimensions 为本迭代固定向量维度 bge-m3=1024。
 */
public record KbHealthView(boolean enabled,
                           boolean ollamaOk,
                           boolean pgOk,
                           long documentCount,
                           long chunkCount,
                           int dimensions) {

    /** 防御性兜底（正常不可达：开关关时请求已被拦截器 503 拦在控制器之前）。 */
    public static KbHealthView disabled() {
        return new KbHealthView(false, false, false, 0L, 0L, 1024);
    }
}
