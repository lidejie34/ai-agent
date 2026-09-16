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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三下拉「都不加载」：RagAdvisor 显式禁用单测——advisor param 携带
 * {@link RagAdvisor#PARAM_KB_DISABLED}=true 时直接放行原始请求：不 embedding、
 * 不 repository.search、不注入检索片段/引用纪律；param 缺席或类型异常
 * 防御为正常检索路径（不误判禁用）。
 */
class RagAdvisorDisabledTest {

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
    void disabledTrue_passesThrough_zeroEmbedZeroSearch() {
        AdvisedRequest req = request("售后政策",
                Map.of(RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE));

        AdvisedRequest out = advisor.augment(req);

        assertThat(out).isSameAs(req); // 原请求逐字节放行
        verifyNoInteractions(embeddingService, repository);
    }

    @Test
    void disabledTrueWithTagsParam_stillPassesThrough_tagsLenientlyIgnored() {
        // kbProjects:[] 时 kbTags 宽松忽略（NFR-3）：即使 tags param 在场也不检索
        AdvisedRequest req = request("售后政策", Map.of(
                RagAdvisor.PARAM_KB_DISABLED, Boolean.TRUE,
                RagAdvisor.PARAM_KB_TAGS, List.of("售后")));

        AdvisedRequest out = advisor.augment(req);

        assertThat(out).isSameAs(req);
        verifyNoInteractions(embeddingService, repository);
    }

    @Test
    void disabledFalse_normalSearchPath() {
        when(embeddingService.embed("售后")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), eq(List.of()), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "政策", 0.9)));

        AdvisedRequest out = advisor.augment(request("售后",
                Map.of(RagAdvisor.PARAM_KB_DISABLED, Boolean.FALSE)));

        assertThat(out.userText()).contains("<retrieved-context>");
        verify(repository).search(any(), eq(4), eq(List.of()), eq(List.of()));
    }

    @Test
    void disabledMalformedType_defensiveNormalSearchPath() {
        // 非 Boolean 类型（如字符串 "true"）防御为未禁用——不误判禁用跳过检索
        when(embeddingService.embed("售后")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4), eq(List.of()), eq(List.of()))).thenReturn(List.of(
                new RagChunkView("售后.md", "政策", 0.9)));

        AdvisedRequest out = advisor.augment(request("售后",
                Map.of(RagAdvisor.PARAM_KB_DISABLED, "true")));

        assertThat(out.userText()).contains("<retrieved-context>");
        verify(repository).search(any(), eq(4), eq(List.of()), eq(List.of()));
    }
}
