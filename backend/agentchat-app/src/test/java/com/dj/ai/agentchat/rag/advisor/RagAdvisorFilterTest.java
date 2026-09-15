package com.dj.ai.agentchat.rag.advisor;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagChunkView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10：RagAdvisor 维度过滤单测——advisor param（kbProject/kbTags）驱动过滤检索；
 * 无 param 走旧两参 search（mock 验证逐字节回归）；param 类型异常防御为不过滤；
 * 过滤后无高于阈值命中仍按原请求放行。
 */
class RagAdvisorFilterTest {

    private RagEmbeddingService embeddingService;
    private KbRepository repository;
    private RagAdvisor advisor;

    @BeforeEach
    void setUp() {
        embeddingService = mock(RagEmbeddingService.class);
        repository = mock(KbRepository.class);
        advisor = new RagAdvisor(embeddingService, repository, new RagProperties());
    }

    private AdvisedRequest request(String userText, Map<String, Object> params) {
        return AdvisedRequest.builder().chatModel(mock(ChatModel.class))
                .userText(userText).advisorParams(params).build();
    }

    @Test
    void noParams_callsSearchWithNullProjectAndEmptyTags() {
        when(embeddingService.embed("售后政策")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), isNull(), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "七天无理由退货", 0.9)));

        AdvisedRequest out = advisor.augment(request("售后政策", Map.of()));

        assertThat(out.userText()).contains("<retrieved-context>");
        // 无 param → 全空维度（仓储层 4 参自委托迭代6 无过滤 SQL 原文）
        verify(repository).search(any(), eq(4), isNull(), eq(List.of()));
    }

    @Test
    void projectOnly_callsFilteredSearchWithEmptyTags() {
        when(embeddingService.embed("售后政策")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), eq("订单域"), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "七天无理由退货", 0.9)));

        AdvisedRequest out = advisor.augment(request("售后政策",
                Map.of(RagAdvisor.PARAM_KB_PROJECT, "订单域")));

        assertThat(out.userText()).contains("七天无理由退货");
        verify(repository).search(any(), eq(4), eq("订单域"), eq(List.of()));
    }

    @Test
    void tagsOnly_callsFilteredSearchWithNullProject() {
        when(embeddingService.embed("退货流程")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), isNull(), eq(List.of("售后", "退货"))))
                .thenReturn(List.of(new RagChunkView("流程.md", "退货三步", 0.8)));

        advisor.augment(request("退货流程",
                Map.of(RagAdvisor.PARAM_KB_TAGS, List.of("售后", "退货"))));

        verify(repository).search(any(), eq(4), isNull(), eq(List.of("售后", "退货")));
    }

    @Test
    void projectAndTags_bothPassed() {
        when(embeddingService.embed("承运")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), eq("物流域"), eq(List.of("承运"))))
                .thenReturn(List.of(new RagChunkView("承运.md", "承运商结算", 0.8)));

        advisor.augment(request("承运", Map.of(
                RagAdvisor.PARAM_KB_PROJECT, "物流域",
                RagAdvisor.PARAM_KB_TAGS, List.of("承运"))));

        verify(repository).search(any(), eq(4), eq("物流域"), eq(List.of("承运")));
    }

    @Test
    void filteredNoHitAboveThreshold_passesThroughOriginalRequest() {
        when(embeddingService.embed("无关问题")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), eq("不存在域"), eq(List.of())))
                .thenReturn(List.of(new RagChunkView("x.md", "弱相关", 0.2)));

        AdvisedRequest req = request("无关问题", Map.of(RagAdvisor.PARAM_KB_PROJECT, "不存在域"));
        AdvisedRequest out = advisor.augment(req);

        assertThat(out).isSameAs(req); // 阈值过滤后无命中 → 原请求放行（不虚构引用）
    }

    @Test
    void malformedParamTypes_defensiveFallbackToUnfiltered() {
        when(embeddingService.embed("售后")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), isNull(), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "政策", 0.9)));

        // project 非 String、tags 非 List → 防御为不过滤（降级铁律不抛错）
        AdvisedRequest out = advisor.augment(request("售后", Map.of(
                RagAdvisor.PARAM_KB_PROJECT, 42,
                RagAdvisor.PARAM_KB_TAGS, "不是列表")));

        assertThat(out.userText()).contains("<retrieved-context>");
        verify(repository).search(any(), eq(4), isNull(), eq(List.of()));
    }

    @Test
    void blankProjectParam_treatedAsAbsent() {
        when(embeddingService.embed("售后")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), isNull(), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "政策", 0.9)));

        advisor.augment(request("售后", Map.of(RagAdvisor.PARAM_KB_PROJECT, "   ")));

        verify(repository).search(any(), eq(4), isNull(), eq(List.of()));
    }
}
