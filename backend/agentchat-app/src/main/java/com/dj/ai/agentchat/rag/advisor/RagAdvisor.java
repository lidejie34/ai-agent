package com.dj.ai.agentchat.rag.advisor;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagChunkView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 常驻检索 Advisor（迭代6）：每轮用户消息 → 本地 bge-m3 向量化 → pgvector 余弦
 * top-k → 阈值过滤 → 把带来源文件名的资料片段拼入本轮 userText，并在 systemText
 * 追加引用与反幻觉约束；模型据此回答并在末尾列出参考文件名（F7/F8）。
 *
 * <p>双接口范式照 {@code RequestLoggingAdvisor}：同步/流式共用 {@link #augment}。
 * order=100，位于请求日志 Advisor(0) 之后。<b>降级铁律</b>：embedding/检索任何异常
 * （Ollama 未启动、PG 不可达）只 warn，原样放行请求——对话永不因 RAG 失败而中断（F10/N3）；
 * 无高于阈值命中时同样原样放行，模型按自身知识回答（F9）。
 */
@Slf4j
public class RagAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    public static final String NAME = "rag-retrieval-advisor";
    public static final int ORDER = 100;

    /** advisor param 键（迭代11，原 kbProject 单值升级）：知识库检索项目过滤（List<String>，任一命中 OR）。 */
    public static final String PARAM_KB_PROJECTS = "kbProjects";
    /** advisor param 键（迭代10）：知识库检索标签过滤（List<String>，任一命中 OR）。 */
    public static final String PARAM_KB_TAGS = "kbTags";

    static final String CITATION_RULE = """
            你可以参考上方 <retrieved-context> 中检索到的知识库资料回答问题。要求：
            1. 回答涉及知识库内容时，在回答末尾另起一行以「参考资料：」列出引用到的文件名
               （按引用顺序、去重，只列真实出现在资料块中的文件名）；
            2. 资料不足以回答时，明确说明知识库中没有相关内容，不得编造文件名或资料中不存在的内容；
            3. <retrieved-context> 之外的问题按你自身知识正常回答，不要强行引用。""";

    private final RagEmbeddingService embeddingService;
    private final KbRepository repository;
    private final int topK;
    private final double minScore;

    public RagAdvisor(RagEmbeddingService embeddingService,
                      KbRepository repository,
                      RagProperties properties) {
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.topK = properties.getRetrieve().getTopK();
        this.minScore = properties.getRetrieve().getMinScore();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        return chain.nextAroundCall(augment(request));
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request,
                                               StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(augment(request));
    }

    /**
     * 检索增强：query embed → top-k → 阈值过滤 → 改写 userText/systemText。
     * 任何异常或无命中返回原请求（包级可见以便单测）。
     */
    AdvisedRequest augment(AdvisedRequest request) {
        String query = request.userText();
        if (query == null || query.isBlank()) {
            return request;
        }
        try {
            float[] vector = embeddingService.embed(query);
            // 迭代10/11：advisor param 携带维度过滤（ChatService/ExecutorClient 注入；
            // 两维全空走迭代6 无过滤 SQL 原文——逐字节回归）
            List<String> kbProjects = paramProjects(request);
            List<String> kbTags = paramTags(request);
            List<RagChunkView> hits = repository.search(vector, topK, kbProjects, kbTags).stream()
                    .filter(h -> h.score() >= minScore)
                    .toList();
            if (hits.isEmpty()) {
                log.debug("RAG 检索无高于阈值 {} 的命中，按原请求放行（projects={}, tags={}）",
                        minScore, kbProjects, kbTags);
                return request;
            }
            String contextBlock = renderContext(hits);
            String augmentedUser = query + "\n\n" + contextBlock;
            String baseSystem = request.systemText();
            String system = (baseSystem == null || baseSystem.isBlank())
                    ? CITATION_RULE : baseSystem + "\n\n" + CITATION_RULE;
            if (kbProjects.isEmpty() && kbTags.isEmpty()) {
                log.info("RAG 检索注入 {} 个片段（topK={}, 阈值={}）", hits.size(), topK, minScore);
            } else {
                log.info("RAG 检索注入 {} 个片段（topK={}, 阈值={}, projects={}, tags={}）",
                        hits.size(), topK, minScore, kbProjects, kbTags);
            }
            return AdvisedRequest.from(request)
                    .userText(augmentedUser)
                    .systemText(system)
                    .build();
        } catch (Exception e) {
            log.warn("RAG 检索增强失败，降级为普通对话（不阻断）: {}", e.getMessage());
            return request;
        }
    }

    /** 读项目过滤 param（迭代11 多选）：缺席 → 空表；逐元素取非空白 String 项（防御性过滤）。 */
    private static List<String> paramProjects(AdvisedRequest request) {
        Object v = request.advisorParams() == null ? null : request.advisorParams().get(PARAM_KB_PROJECTS);
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(s -> !s.isBlank()).toList();
    }

    /** 读标签过滤 param（迭代10）：缺席 → 空表；逐元素取 String 项（防御性过滤非字符串）。 */
    private static List<String> paramTags(AdvisedRequest request) {
        Object v = request.advisorParams() == null ? null : request.advisorParams().get(PARAM_KB_TAGS);
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * 渲染资料块：每片段编号 + 来源文件名 + 原文；同一文件的多个片段分别编号
     * （模型侧引用文件名去重由 CITATION_RULE 约束）。
     */
    static String renderContext(List<RagChunkView> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("<retrieved-context>\n");
        sb.append("以下是从知识库检索到的相关资料（仅供参考，可能不全）：\n");
        int index = 1;
        for (RagChunkView hit : hits) {
            sb.append('[').append(index++).append("] 来源文件：")
                    .append(hit.fileName()).append('\n')
                    .append(hit.content().strip()).append('\n');
        }
        sb.append("</retrieved-context>");
        return sb.toString();
    }
}
