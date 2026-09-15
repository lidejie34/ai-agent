package com.dj.ai.agentchat.rag.admin.service;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.chunk.TextChunker;
import com.dj.ai.agentchat.rag.dim.DimRepository;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10：KbDocumentService 维度元数据单测——上传打标（规整后落库、非法 400 且
 * fail-fast 不浪费 embedding）、PATCH 全量替换（200/404/非法前置拦截）、
 * 列表过滤透传（全空回落全量）、reindex 不动元数据。
 */
class KbDocumentServiceMetaTest {

    private KbRepository repository;
    private RagEmbeddingService embeddingService;
    private RagProperties properties;
    private DimRepository dimRepository;
    private KbDocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(KbRepository.class);
        embeddingService = mock(RagEmbeddingService.class);
        RagSchemaInitializer schemaInitializer = mock(RagSchemaInitializer.class);
        dimRepository = mock(DimRepository.class);
        // 默认项目已受管存在（迭代10 追加：写侧项目须先维护）；不存在场景由用例显式 stub false
        when(dimRepository.projectExists(anyString())).thenReturn(true);
        properties = new RagProperties();
        service = new KbDocumentService(repository, embeddingService,
                new TextChunker(properties.getChunk()), properties, schemaInitializer,
                dimRepository);
    }

    private byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private RagDocument doc(long id, String project, List<String> tags) {
        return new RagDocument(id, "x.md", 100, "# 制度\n正文内容", "hash",
                1, RagDocument.STATUS_READY, null, null, null, project, tags);
    }

    private void stubReady(long id) {
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(repository.saveReady(anyString(), anyInt(), anyString(), anyString(),
                anyList(), anyList(), any(), anyList())).thenReturn(id);
        when(repository.findById(id)).thenReturn(Optional.of(doc(id, null, List.of())));
    }

    @Test
    void upload_withMeta_normalizesAndPersistsProjectAndTags() {
        stubReady(7L);

        service.upload("售后.md", utf8("七天无理由退货"),
                " 订单域 ", List.of("售后", " 退货 ", "售后", ""));

        // trim / 去空白 / 保序去重后落库
        verify(repository).saveReady(eq("售后.md"), anyInt(), anyString(), anyString(),
                anyList(), anyList(), eq("订单域"), eq(List.of("售后", "退货")));
    }

    @Test
    void upload_invalidProject_rejected400BeforeEmbedding() {
        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容"), "含,逗号", List.of()))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_PROJECT);
        // fail-fast：非法元数据不浪费 embedding、不落库
        verify(embeddingService, never()).embedBatch(anyList());
        verify(repository, never()).saveReady(anyString(), anyInt(), anyString(),
                anyString(), anyList(), anyList(), any(), anyList());
    }

    @Test
    void upload_invalidTagsComma_rejected400BeforeEmbedding() {
        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容"), null, List.of("a,b")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_TAGS);
        verify(embeddingService, never()).embedBatch(anyList());
    }

    @Test
    void upload_tooManyTags_rejected400() {
        List<String> nine = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9");
        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容"), null, nine))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_TAGS);
        verify(embeddingService, never()).embedBatch(anyList());
    }

    @Test
    void updateMeta_happy_fullReplaceReturnsLatestView() {
        when(repository.updateMeta(7L, "物流域", List.of("承运"))).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(doc(7L, "物流域", List.of("承运"))));

        RagDocument view = service.updateMeta(7L, " 物流域 ", List.of("承运", "承运"));

        verify(repository).updateMeta(7L, "物流域", List.of("承运"));
        assertThat(view.project()).isEqualTo("物流域");
        assertThat(view.tags()).containsExactly("承运");
        assertThat(view.content()).isNull();
    }

    @Test
    void upload_unknownProject_rejected400BeforeEmbedding() {
        when(dimRepository.projectExists("幽灵域")).thenReturn(false);

        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容"), "幽灵域", List.of()))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_PROJECT);
        // fail-fast：未受管项目不浪费 embedding、不落库
        verify(embeddingService, never()).embedBatch(anyList());
        verify(repository, never()).saveReady(anyString(), anyInt(), anyString(),
                anyString(), anyList(), anyList(), any(), anyList());
    }

    @Test
    void upload_nullProject_skipsManagedCheck() {
        stubReady(8L);

        service.upload("无归属.md", utf8("正文内容"), null, List.of());

        verify(dimRepository, never()).projectExists(anyString());
        verify(repository).saveReady(eq("无归属.md"), anyInt(), anyString(), anyString(),
                anyList(), anyList(), isNull(), eq(List.of()));
    }

    @Test
    void updateMeta_unknownProject_rejectedBeforeStore() {
        when(dimRepository.projectExists("幽灵域")).thenReturn(false);

        assertThatThrownBy(() -> service.updateMeta(7L, "幽灵域", List.of()))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_PROJECT);
        verify(repository, never()).updateMeta(anyLong(), any(), anyList());
    }

    @Test
    void updateMeta_zeroRows_throwsNotFound() {
        when(repository.updateMeta(anyLong(), any(), anyList())).thenReturn(0);

        assertThatThrownBy(() -> service.updateMeta(99L, null, List.of()))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_NOT_FOUND);
    }

    @Test
    void updateMeta_invalidTags_rejectedBeforeStore() {
        assertThatThrownBy(() -> service.updateMeta(7L, null, List.of("a,b")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_TAGS);
        verify(repository, never()).updateMeta(anyLong(), any(), anyList());
    }

    @Test
    void list_withFilters_delegatesToFilteredQuery() {
        when(repository.listDocuments("订单域", "售后"))
                .thenReturn(List.of(doc(7L, "订单域", List.of("售后"))));

        List<RagDocument> docs = service.list("订单域", "售后");

        assertThat(docs).hasSize(1);
        verify(repository).listDocuments("订单域", "售后");
        verify(repository, never()).listDocuments();
    }

    @Test
    void list_blankFilters_fallsBackToUnfiltered() {
        service.list("  ", null);

        verify(repository).listDocuments();
        verify(repository, never()).listDocuments(any(), any());
    }

    @Test
    void list_invalidTag_rejected400SameAsWriteSide() {
        assertThatThrownBy(() -> service.list(null, "a,b"))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_TAGS);
        verify(repository, never()).listDocuments(any(), any());
    }

    @Test
    void reindex_neverTouchesMetaColumns() {
        when(repository.findById(3L)).thenReturn(Optional.of(doc(3L, "订单域", List.of("售后"))));
        when(embeddingService.embedBatch(anyList()))
                .thenAnswer(inv -> inv.<List<String>>getArgument(0).stream()
                        .map(x -> new float[]{0.3f}).toList());

        service.reindex(3L);

        verify(repository).replaceChunks(eq(3L), anyString(), anyInt(), anyList(), anyList());
        // 重建只替换切片/向量，维度元数据原样保留
        verify(repository, never()).updateMeta(anyLong(), any(), anyList());
    }
}
