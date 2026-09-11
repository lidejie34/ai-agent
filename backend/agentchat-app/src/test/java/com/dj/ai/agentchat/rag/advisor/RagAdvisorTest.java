package com.dj.ai.agentchat.rag.advisor;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingException;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagChunkView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T7：RagAdvisor 单测——阈值过滤、userText/systemText 改写、来源文件名标注、
 * 无命中/embedding 失败/检索失败/空 query 全部原样降级、同步与流式链透传。
 */
class RagAdvisorTest {

    private RagEmbeddingService embeddingService;
    private KbRepository repository;
    private RagAdvisor advisor;

    @BeforeEach
    void setUp() {
        embeddingService = mock(RagEmbeddingService.class);
        repository = mock(KbRepository.class);
        advisor = new RagAdvisor(embeddingService, repository, new RagProperties());
    }

    private AdvisedRequest request(String userText) {
        return AdvisedRequest.builder().chatModel(mock(ChatModel.class))
                .userText(userText).build();
    }

    private AdvisedRequest requestWithSystem(String userText, String systemText) {
        return AdvisedRequest.builder().chatModel(mock(ChatModel.class))
                .userText(userText).systemText(systemText).build();
    }

    @Test
    void nameAndOrder_afterLoggingAdvisor() {
        assertThat(advisor.getName()).isEqualTo("rag-retrieval-advisor");
        assertThat(advisor.getOrder()).isGreaterThan(0);
    }

    @Test
    void augment_hitsAboveThreshold_injectsContextAndCitationRule() {
        when(embeddingService.embed("差旅报销标准")).thenReturn(new float[]{0.1f});
        when(repository.search(any(), eq(4))).thenReturn(List.of(
                new RagChunkView("差旅制度.md", "经济舱实报实销，住宿上限 500 元/晚", 0.74),
                new RagChunkView("差旅制度.md", "出差需提前审批", 0.61),
                new RagChunkView("无关.md", "今晚月色真美", 0.31)));

        AdvisedRequest result = advisor.augment(request("差旅报销标准"));

        assertThat(result.userText())
                .startsWith("差旅报销标准\n\n<retrieved-context>")
                .contains("[1] 来源文件：差旅制度.md")
                .contains("住宿上限 500 元/晚")
                .contains("[2] 来源文件：差旅制度.md")
                .contains("出差需提前审批")
                .endsWith("</retrieved-context>")
                .doesNotContain("今晚月色真美");
        assertThat(result.systemText())
                .contains("参考资料")
                .contains("不得编造文件名");
        verify(repository).search(new float[]{0.1f}, 4);
    }

    @Test
    void augment_thresholdFiltered_allBelow_returnsSameRequest() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt())).thenReturn(List.of(
                new RagChunkView("a.md", "弱相关", 0.44)));

        AdvisedRequest original = request("天气怎么样");
        assertThat(advisor.augment(original)).isSameAs(original);
    }

    @Test
    void augment_exactlyAtThreshold_passes() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt())).thenReturn(List.of(
                new RagChunkView("a.md", "卡线命中", 0.45)));

        AdvisedRequest result = advisor.augment(request("q"));
        assertThat(result.userText()).contains("卡线命中");
    }

    @Test
    void augment_existingSystemText_ruleAppendedAfterIt() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt())).thenReturn(List.of(
                new RagChunkView("a.md", "片段", 0.9)));

        AdvisedRequest result = advisor.augment(requestWithSystem("q", "你是编排执行器"));
        assertThat(result.systemText())
                .startsWith("你是编排执行器\n\n")
                .contains("不得编造文件名");
    }

    @Test
    void augment_nullUserTextOnToolResponseTurn_skipsEmbeddingAndReturnsSame() {
        // M7 不允许构造空白 userText；null userText 仅在工具结果续轮场景（消息含 ToolResponse）出现
        AdvisedRequest original = AdvisedRequest.builder().chatModel(mock(ChatModel.class))
                .messages(List.of(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("t1", "fn", "工具返回")))))
                .build();
        assertThat(original.userText()).isNull();

        assertThat(advisor.augment(original)).isSameAs(original);
        verify(embeddingService, never()).embed(any());
        verify(repository, never()).search(any(), anyInt());
    }

    @Test
    void augment_embeddingFails_degradesToSameRequest() {
        when(embeddingService.embed(any()))
                .thenThrow(new RagEmbeddingException("Ollama embedding 连接/超时失败 model=bge-m3", null));
        AdvisedRequest original = request("问题");
        assertThat(advisor.augment(original)).isSameAs(original);
        verify(repository, never()).search(any(), anyInt());
    }

    @Test
    void augment_searchFails_degradesToSameRequest() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt()))
                .thenThrow(new RuntimeException("relation rag_chunk does not exist"));
        AdvisedRequest original = request("问题");
        assertThat(advisor.augment(original)).isSameAs(original);
    }

    @Test
    void aroundCall_passesAugmentedRequestToChain_andReturnsChainResponse() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt())).thenReturn(List.of(
                new RagChunkView("制度.md", "正文", 0.88)));
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        AdvisedResponse chainResponse = mock(AdvisedResponse.class);
        ArgumentCaptor<AdvisedRequest> captor = ArgumentCaptor.forClass(AdvisedRequest.class);
        when(chain.nextAroundCall(captor.capture())).thenReturn(chainResponse);

        AdvisedResponse response = advisor.aroundCall(request("制度怎么说"), chain);

        assertThat(response).isSameAs(chainResponse);
        assertThat(captor.getValue().userText()).contains("<retrieved-context>");
    }

    @Test
    void aroundStream_passesAugmentedRequestToChain_andReturnsChainFlux() {
        when(embeddingService.embed(any())).thenReturn(new float[]{0f});
        when(repository.search(any(), anyInt())).thenReturn(List.of(
                new RagChunkView("制度.md", "正文", 0.88)));
        StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
        AdvisedResponse item = mock(AdvisedResponse.class);
        ArgumentCaptor<AdvisedRequest> captor = ArgumentCaptor.forClass(AdvisedRequest.class);
        when(chain.nextAroundStream(captor.capture())).thenReturn(Flux.just(item));

        List<AdvisedResponse> collected = advisor
                .aroundStream(request("制度怎么说"), chain).collectList().block();

        assertThat(collected).containsExactly(item);
        assertThat(captor.getValue().userText()).contains("<retrieved-context>");
    }

    @Test
    void aroundStream_degradedWhenEmbeddingDown_chainStillReceivesOriginalRequest() {
        when(embeddingService.embed(any()))
                .thenThrow(new RagEmbeddingException("连接被拒绝", null));
        StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
        when(chain.nextAroundStream(any())).thenReturn(Flux.empty());

        advisor.aroundStream(request("正常问题"), chain).collectList().block();

        ArgumentCaptor<AdvisedRequest> captor = ArgumentCaptor.forClass(AdvisedRequest.class);
        verify(chain).nextAroundStream(captor.capture());
        assertThat(captor.getValue().userText()).isEqualTo("正常问题");
    }
}
