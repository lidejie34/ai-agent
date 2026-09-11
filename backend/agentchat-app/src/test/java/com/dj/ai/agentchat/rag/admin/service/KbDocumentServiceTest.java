package com.dj.ai.agentchat.rag.admin.service;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.chunk.TextChunker;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingException;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T6：KbDocumentService 编排单测——扩展名/大小/编码/空文件/路径名校验、
 * 成功落库参数（sha1/READY/片数）、embedding 与 store 失败落 FAILED+错误码、
 * reindex 成功/不存在/失败、删除不存在 404。
 */
class KbDocumentServiceTest {

    private KbRepository repository;
    private RagEmbeddingService embeddingService;
    private RagSchemaInitializer schemaInitializer;
    private RagProperties properties;
    private KbDocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(KbRepository.class);
        embeddingService = mock(RagEmbeddingService.class);
        schemaInitializer = mock(RagSchemaInitializer.class);
        properties = new RagProperties();
        TextChunker chunker = new TextChunker(properties.getChunk());
        service = new KbDocumentService(repository, embeddingService, chunker,
                properties, schemaInitializer);
    }

    private byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void upload_pdfExtension_rejected400BeforeEmbeddingOrStore() {
        assertThatThrownBy(() -> service.upload("方案.pdf", utf8("内容")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
        verify(embeddingService, never()).embedBatch(anyList());
        verify(repository, never()).saveReady(anyString(), anyInt(), anyString(),
                anyString(), anyList(), anyList());
        verify(schemaInitializer).ensureSchema();
    }

    @Test
    void upload_noExtension_rejected() {
        assertThatThrownBy(() -> service.upload("README", utf8("x")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
    }

    @Test
    void upload_uppercaseMd_accepted() {
        stubReady(1L);
        RagDocument doc = service.upload("制度.MD", utf8("# 标题\n正文"));
        assertThat(doc.status()).isEqualTo(RagDocument.STATUS_READY);
    }

    @Test
    void upload_markdownExt_accepted() {
        stubReady(2L);
        assertThat(service.upload("制度.markdown", utf8("正文内容")).status())
                .isEqualTo(RagDocument.STATUS_READY);
    }

    @Test
    void upload_emptyBytes_rejectedAsInvalidFile() {
        assertThatThrownBy(() -> service.upload("a.md", new byte[0]))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
    }

    @Test
    void upload_blankText_rejected() {
        assertThatThrownBy(() -> service.upload("a.txt", utf8("  \n \r\n")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
    }

    @Test
    void upload_oversize_rejectedWithFileTooLarge() {
        properties.getUpload().setMaxFileBytes(10);
        assertThatThrownBy(() -> service.upload("big.md", utf8("0123456789ABCDEF")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_FILE_TOO_LARGE);
        verify(embeddingService, never()).embedBatch(anyList());
    }

    @Test
    void upload_invalidUtf8_rejected() {
        // 0xC0 0xAF 是非法 UTF-8 超长序列
        byte[] bad = new byte[]{(byte) 0x23, (byte) 0x20, (byte) 0xC0, (byte) 0xAF};
        assertThatThrownBy(() -> service.upload("bad.md", bad))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
    }

    @Test
    void upload_pathSeparatorInName_rejected() {
        assertThatThrownBy(() -> service.upload("../escape.md", utf8("x")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_FILE);
        assertThatThrownBy(() -> service.upload("dir\\a.md", utf8("x")))
                .isInstanceOf(KbAdminException.class);
    }

    @Test
    void upload_chunkCapExceeded_rejected() {
        // max-chars=10,overlap=0 → 11 字文本 2 片；chunker 参数在构造时固化，需先换实例
        properties.getUpload().setMaxChunks(1);
        service = new KbDocumentService(repository, embeddingService,
                new TextChunker(10, 0, true), properties, schemaInitializer);
        assertThatThrownBy(() -> service.upload("a.md", utf8("一二三四五六七八九十一二")))
                .isInstanceOf(KbAdminException.class)
                .hasMessageContaining("切片数");
        verify(embeddingService, never()).embedBatch(anyList());
    }

    @Test
    void upload_happy_persistsWithSha1ReadyChunks_andReturnsView() throws Exception {
        String content = "# 差旅制度\n\n经济舱实报实销，住宿每晚不超过 500 元。";
        byte[] bytes = utf8(content);
        String expectedHash = HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA-1").digest(bytes));
        stubReady(7L);
        when(embeddingService.embedBatch(anyList()))
                .thenAnswer(inv -> inv.<List<String>>getArgument(0).stream()
                        .map(x -> new float[]{0.1f, 0.2f}).toList());

        RagDocument view = service.upload("差旅制度.md", bytes);

        assertThat(view.id()).isEqualTo(7L);
        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.content()).isNull();
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveReady(eq("差旅制度.md"), eq(bytes.length), eq(content),
                hashCaptor.capture(), chunksCaptor.capture(), anyList());
        assertThat(hashCaptor.getValue()).isEqualTo(expectedHash);
        assertThat(chunksCaptor.getValue()).hasSize(1);
    }

    @Test
    void upload_embeddingFailure_savesFailedRow_andThrows502Code() {
        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new RagEmbeddingException("连接/超时失败: Read timed out", null));

        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_EMBEDDING_FAILED);

        // FAILED 行保留原文与 hash，片数 0
        verify(repository).saveFailed(eq("a.md"), anyInt(), eq("正文内容"),
                anyString(), org.mockito.ArgumentMatchers.contains("Read timed out"));
        verify(repository, never()).saveReady(anyString(), anyInt(), anyString(),
                anyString(), anyList(), anyList());
    }

    @Test
    void upload_storeFailure_savesFailedRow_andThrowsStoreCode() {
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{1f}));
        when(repository.saveReady(anyString(), anyInt(), anyString(), anyString(),
                anyList(), anyList()))
                .thenThrow(new DataAccessResourceFailureException("relation does not exist"));
        when(repository.saveFailed(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(-1L);

        assertThatThrownBy(() -> service.upload("a.md", utf8("正文内容")))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_STORE_FAILED);
        verify(repository).saveFailed(anyString(), anyInt(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("relation does not exist"));
    }

    @Test
    void list_delegatesToRepository() {
        service.list();
        verify(repository).listDocuments();
    }

    @Test
    void delete_existing_ok() {
        when(repository.findById(5L)).thenReturn(Optional.of(doc(5L, "a.md", "READY")));
        service.delete(5L);
        verify(repository).deleteById(5L);
    }

    @Test
    void delete_missing_throwsNotFound() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_NOT_FOUND);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void reindex_happy_reembedsAndReplacesChunks() {
        RagDocument oldDoc = doc(9L, "制度.md", RagDocument.STATUS_READY);
        when(repository.findById(9L)).thenReturn(Optional.of(oldDoc));
        when(embeddingService.embedBatch(anyList()))
                .thenAnswer(inv -> inv.<List<String>>getArgument(0).stream()
                        .map(x -> new float[]{0.3f}).toList());

        RagDocument view = service.reindex(9L);

        ArgumentCaptor<List<String>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceChunks(eq(9L), eq("制度.md"), eq(1),
                chunksCaptor.capture(), anyList());
        assertThat(chunksCaptor.getValue().get(0)).contains("# 制度");
        assertThat(view.status()).isEqualTo("READY");
    }

    @Test
    void reindex_missing_throwsNotFound() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reindex(404L))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_NOT_FOUND);
    }

    @Test
    void reindex_embeddingFailure_marksFailedAndThrows() {
        when(repository.findById(3L)).thenReturn(Optional.of(doc(3L, "a.md", "READY")));
        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new RagEmbeddingException("Ollama 500", null));

        assertThatThrownBy(() -> service.reindex(3L))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_EMBEDDING_FAILED);
        verify(repository).markFailed(eq(3L), org.mockito.ArgumentMatchers.contains("Ollama 500"));
        verify(repository, never()).replaceChunks(anyLong(), anyString(), anyInt(),
                anyList(), anyList());
    }

    private void stubReady(long id) {
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(repository.saveReady(anyString(), anyInt(), anyString(), anyString(),
                anyList(), anyList())).thenReturn(id);
        when(repository.findById(id)).thenReturn(Optional.of(doc(id, "x.md", "READY")));
    }

    private RagDocument doc(long id, String name, String status) {
        return new RagDocument(id, name, 100, "# 制度\n正文内容", "hash",
                1, status, null, null, null);
    }
}
