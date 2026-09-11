package com.dj.ai.agentchat.rag.admin.service;

import com.dj.ai.agentchat.rag.admin.dto.KbHealthView;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.store.KbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T10（迭代6）：KbHealthService——Ollama ping 与 PG 计数分项探测、各自独立降级；
 * dimensions 固定 1024；enabled 在 service 可达时恒 true。
 */
class KbHealthServiceTest {

    private RagEmbeddingService embeddingService;
    private KbRepository repository;
    private KbHealthService healthService;

    @BeforeEach
    void setUp() {
        embeddingService = mock(RagEmbeddingService.class);
        repository = mock(KbRepository.class);
        healthService = new KbHealthService(embeddingService, repository);
    }

    @Test
    void allUp_enabledTrue_ollamaPgOk_countsAndDimensions() {
        when(embeddingService.ping()).thenReturn(true);
        when(repository.countDocuments()).thenReturn(3L);
        when(repository.countChunks()).thenReturn(27L);

        KbHealthView view = healthService.health();

        assertThat(view.enabled()).isTrue();
        assertThat(view.ollamaOk()).isTrue();
        assertThat(view.pgOk()).isTrue();
        assertThat(view.documentCount()).isEqualTo(3L);
        assertThat(view.chunkCount()).isEqualTo(27L);
        assertThat(view.dimensions()).isEqualTo(1024);
    }

    @Test
    void emptyLibrary_countsZero_butStillHealthy() {
        when(embeddingService.ping()).thenReturn(true);
        when(repository.countDocuments()).thenReturn(0L);
        when(repository.countChunks()).thenReturn(0L);

        KbHealthView view = healthService.health();

        assertThat(view.pgOk()).isTrue();
        assertThat(view.documentCount()).isZero();
        assertThat(view.chunkCount()).isZero();
    }

    @Test
    void ollamaDown_pingFalse_ollamaNotOkButPgOk() {
        when(embeddingService.ping()).thenReturn(false);
        when(repository.countDocuments()).thenReturn(1L);
        when(repository.countChunks()).thenReturn(5L);

        KbHealthView view = healthService.health();

        assertThat(view.ollamaOk()).isFalse();
        assertThat(view.pgOk()).isTrue();
        assertThat(view.documentCount()).isEqualTo(1L);
        assertThat(view.chunkCount()).isEqualTo(5L);
    }

    @Test
    void ollamaProbeThrows_survivedAsOllamaNotOk() {
        // ping() 自身已吞异常，这里防御它再次抛出的情况
        when(embeddingService.ping()).thenThrow(new RuntimeException("connection refused"));
        when(repository.countDocuments()).thenReturn(0L);
        when(repository.countChunks()).thenReturn(0L);

        KbHealthView view = healthService.health();

        assertThat(view.ollamaOk()).isFalse();
        assertThat(view.pgOk()).isTrue();
    }

    @Test
    void pgCountDocumentsThrows_pgNotOk_zeroCounts_ollamaProbeStillRan() {
        when(embeddingService.ping()).thenReturn(true);
        when(repository.countDocuments()).thenThrow(new RuntimeException("relation does not exist"));

        KbHealthView view = healthService.health();

        assertThat(view.pgOk()).isFalse();
        assertThat(view.documentCount()).isZero();
        assertThat(view.chunkCount()).isZero();
        assertThat(view.ollamaOk()).isTrue();
        verify(embeddingService).ping();
    }

    @Test
    void pgCountChunksThrows_pgNotOk_zeroCounts() {
        when(embeddingService.ping()).thenReturn(false);
        when(repository.countDocuments()).thenReturn(2L);
        when(repository.countChunks()).thenThrow(new RuntimeException("pgvector extension missing"));

        KbHealthView view = healthService.health();

        assertThat(view.pgOk()).isFalse();
        assertThat(view.documentCount()).isZero();
        assertThat(view.chunkCount()).isZero();
        assertThat(view.ollamaOk()).isFalse();
    }
}
