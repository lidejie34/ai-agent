package com.dj.ai.agentchat.rag.embed;

import com.dj.ai.agentchat.rag.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4：RagEmbeddingService 纯单测（mock EmbeddingModel）——
 * 分批（16/批，单次 HTTP 语义）、顺序对齐、空入参、数量校验、异常分类、探活。
 * 真实 Ollama 调用留待 SMOKE-RAG。
 */
class RagEmbeddingServiceTest {

    private EmbeddingModel embeddingModel;
    private RagEmbeddingService service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        service = new RagEmbeddingService(embeddingModel, new RagProperties());
    }

    private float[] vector(float seed) {
        return new float[]{seed, seed + 1, seed + 2};
    }

    @Test
    void embed_single_delegatesToModel() {
        when(embeddingModel.embed("你好")).thenReturn(vector(1));
        assertThat(service.embed("你好")).containsExactly(1f, 2f, 3f);
        verify(embeddingModel).embed("你好");
    }

    @Test
    void embedBatch_empty_doesNotCallModel() {
        assertThat(service.embedBatch(List.of())).isEmpty();
        verify(embeddingModel, never()).embed(anyList());
    }

    @Test
    void embedBatch_within16_singleRequest() {
        List<String> texts = List.of("a", "b", "c");
        when(embeddingModel.embed(texts)).thenReturn(List.of(vector(0), vector(10), vector(20)));

        List<float[]> result = service.embedBatch(texts);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).containsExactly(0f, 1f, 2f);
        assertThat(result.get(2)).containsExactly(20f, 21f, 22f);
        verify(embeddingModel, times(1)).embed(anyList());
    }

    @Test
    void embedBatch_33_texts_partitioned16_16_1_orderPreserved() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            texts.add("t" + i);
        }
        // 按入参批次回显不同向量，校验顺序对齐
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> batch = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (String t : batch) {
                out.add(vector(Integer.parseInt(t.substring(1))));
            }
            return out;
        });

        List<float[]> result = service.embedBatch(texts);

        assertThat(result).hasSize(33);
        assertThat(result.get(0)[0]).isEqualTo(0f);
        assertThat(result.get(15)[0]).isEqualTo(15f);
        assertThat(result.get(16)[0]).isEqualTo(16f);
        assertThat(result.get(32)[0]).isEqualTo(32f);
        verify(embeddingModel, times(3)).embed(anyList()); // 16 + 16 + 1
        verify(embeddingModel, never()).embed(anyString()); // 不做逐条调用
    }

    @Test
    void embedBatch_exactly32_twoRequests() {
        // 恰好两批：16 + 16
        List<String> thirtyTwo = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            thirtyTwo.add("s" + i);
        }
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> batch = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            batch.forEach(t -> out.add(vector(0)));
            return out;
        });

        assertThat(service.embedBatch(thirtyTwo)).hasSize(32);
        verify(embeddingModel, times(2)).embed(anyList());
    }

    @Test
    void embedBatch_returnCountMismatch_throwsRagEmbeddingException() {
        when(embeddingModel.embed(List.of("a", "b")))
                .thenReturn(List.of(vector(0))); // 少一条

        assertThatThrownBy(() -> service.embedBatch(List.of("a", "b")))
                .isInstanceOf(RagEmbeddingException.class)
                .hasMessageContaining("返回数量异常");
    }

    @Test
    void embed_modelThrowsGenericException_wrappedWithModelHint() {
        when(embeddingModel.embed("q"))
                .thenThrow(new RuntimeException("400 invalid input"));

        assertThatThrownBy(() -> service.embed("q"))
                .isInstanceOf(RagEmbeddingException.class)
                .hasMessageContaining("Ollama embedding 调用失败")
                .hasMessageContaining("model=bge-m3");
    }

    @Test
    void embed_ioTimeout_messageClassifiedAsConnectionFailure() {
        when(embeddingModel.embed("q"))
                .thenThrow(new ResourceAccessException("I/O error on POST: Read timed out"));

        assertThatThrownBy(() -> service.embed("q"))
                .isInstanceOf(RagEmbeddingException.class)
                .hasMessageContaining("连接/超时失败");
    }

    @Test
    void ping_success_returnsTrue() {
        when(embeddingModel.embed("ping")).thenReturn(vector(0));
        assertThat(service.ping()).isTrue();
    }

    @Test
    void ping_failure_returnsFalseWithoutThrowing() {
        when(embeddingModel.embed("ping"))
                .thenThrow(new ResourceAccessException("connect refused"));
        assertThat(service.ping()).isFalse();
    }
}
