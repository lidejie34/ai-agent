package com.dj.ai.agentchat.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识库配置（迭代6，前缀 {@code app.rag}）。
 *
 * <p>语义备注：
 * <ul>
 *   <li>{@code enabled} 默认 false（matchIfMissing=false）：关闭时 RAG 全家桶不装配——
 *       不初始化 PG 连接池、不创建 Ollama 客户端、不挂载 RagAdvisor，对话链路与迭代5
 *       逐字节一致；</li>
 *   <li>PG 为独立第二数据源（绑定 {@code app.rag.datasource.*}），MySQL 仍是
 *       {@code @Primary}；</li>
 *   <li>向量维度固定 1024（bge-m3），换模型需重建切片表；</li>
 *   <li>{@code upload.allowedExt} 为小写无点扩展名白名单。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** RAG 总开关：false（默认）时 RAG 全家桶不装配。 */
    private boolean enabled = false;

    /** pgvector 第二数据源配置。 */
    private Datasource datasource = new Datasource();

    /** 本机 Ollama（OpenAI 兼容 /v1/embeddings）配置。 */
    private Ollama ollama = new Ollama();

    /** 文本切片策略。 */
    private Chunk chunk = new Chunk();

    /** 检索参数。 */
    private Retrieve retrieve = new Retrieve();

    /** 上传约束。 */
    private Upload upload = new Upload();

    @Data
    public static class Datasource {
        /** pgvector JDBC URL，指向 docker db-postgres-1 映射端口与 ai_vector 库。 */
        private String jdbcUrl = "jdbc:postgresql://127.0.0.1:15432/ai_vector";
        private String username = "postgres";
        private String password = "postgres";
    }

    @Data
    public static class Ollama {
        /** Ollama 服务基址（OpenAI 兼容端点由实现侧拼 /v1/embeddings）。 */
        private String baseUrl = "http://localhost:11434";
        /** Embedding 模型；bge-m3 输出 1024 维。 */
        private String model = "bge-m3";
        /** 单次 embedding HTTP 超时（毫秒）。 */
        private int timeoutMs = 10000;
    }

    @Data
    public static class Chunk {
        /** 单片段最大字符数。 */
        private int maxChars = 500;
        /** 相邻片段重叠字符数。 */
        private int overlap = 80;
        /** 是否按 Markdown 标题（#{1,3}）先切段。 */
        private boolean headingAware = true;
    }

    @Data
    public static class Retrieve {
        /** 每轮检索返回的候选片段数。 */
        private int topK = 4;
        /** 余弦相似度阈值（应用层过滤）；低于此值不注入。 */
        private double minScore = 0.45d;
    }

    @Data
    public static class Upload {
        /** 单文件大小上限（字节），默认 10MB（须 ≤ multipart 上限）。 */
        private long maxFileBytes = 10L * 1024 * 1024;
        /** 允许的扩展名（小写、不带点）。 */
        private List<String> allowedExt = new ArrayList<>(List.of("md", "markdown", "txt"));
        /**
         * 单文档切片数硬顶（同步上传保护：2000 片 ≈ 125 批 × 0.3s ≈ 40s 实测上限内）；
         * 超限 400 提示拆分文档。
         */
        private int maxChunks = 2000;
    }
}
