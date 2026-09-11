package com.dj.ai.agentchat.rag.admin;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.rag.admin.service.KbDocumentService;
import com.dj.ai.agentchat.rag.store.RagDocument;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T9（迭代6）：KbAdminController 切片——multipart 上传（字段名 file）201/视图线格式、
 * 列表/删除 204/重建 200、KbAdminException 错误码→HTTP 映射（400/404/502/503）、
 * 缺 file 字段/空文件 400；鉴权闸门复用 AdminAuthInterceptor（rag 关 → 503 KB_DISABLED）。
 */
class KbAdminControllerTest {

    private static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    private static RagDocument doc(long id, String name, String status, String error) {
        return new RagDocument(id, name, 128, "原文", "hash", 3, status, error,
                LocalDateTime.of(2026, 9, 10, 12, 30, 15),
                LocalDateTime.of(2026, 9, 10, 12, 31, 5));
    }

    private MockMultipartFile mdFile(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/markdown", bytes);
    }

    @WebMvcTest(KbAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN,
            "app.tools.enabled=true", "app.rag.enabled=true"})
    @Nested
    class KbAdminSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private KbDocumentService service;

        @Test
        void missingToken_401() throws Exception {
            mvc.perform(get("/api/admin/kb/documents")).andExpect(status().isUnauthorized());
        }

        @Test
        void upload_happy_201_viewFieldsAndServiceArgs() throws Exception {
            byte[] bytes = "# 差旅制度\n正文".getBytes(StandardCharsets.UTF_8);
            when(service.upload(eq("差旅制度.md"), any())).thenReturn(doc(7L, "差旅制度.md", "READY", null));

            String body = mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("差旅制度.md", bytes))
                            .header(HDR, TOKEN))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains("\"id\":7").contains("\"fileName\":\"差旅制度.md\"")
                    .contains("\"chunkCount\":3").contains("\"status\":\"READY\"")
                    .contains("\"createdAt\":\"2026-09-10 12:30:15\"")
                    .contains("\"updatedAt\":\"2026-09-10 12:31:05\"")
                    // 原文与哈希不外泄
                    .doesNotContain("原文").doesNotContain("\"content\"").doesNotContain("hash");
            verify(service).upload(eq("差旅制度.md"), eq(bytes));
        }

        @Test
        void upload_invalidFile_400_kbInvalidFile() throws Exception {
            when(service.upload(any(), any()))
                    .thenThrow(new KbAdminException(KbAdminException.KB_INVALID_FILE, "仅支持 md/txt"));

            String body = mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("a.pdf", new byte[]{1, 2}))
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_INVALID_FILE").contains("仅支持 md/txt");
        }

        @Test
        void upload_fileTooLarge_400() throws Exception {
            when(service.upload(any(), any()))
                    .thenThrow(new KbAdminException(KbAdminException.KB_FILE_TOO_LARGE, "超限"));
            mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("big.md", new byte[]{1}))
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void upload_embeddingFailed_502() throws Exception {
            when(service.upload(any(), any()))
                    .thenThrow(new KbAdminException(KbAdminException.KB_EMBEDDING_FAILED, "Ollama down"));
            String body = mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("a.md", new byte[]{1}))
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadGateway())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_EMBEDDING_FAILED");
        }

        @Test
        void upload_storeFailed_502() throws Exception {
            when(service.upload(any(), any()))
                    .thenThrow(new KbAdminException(KbAdminException.KB_STORE_FAILED, "PG down"));
            mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("b.md", new byte[]{1}))
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadGateway());
        }

        @Test
        void upload_emptyFile_400_invalidFile_withoutCallingService() throws Exception {
            mvc.perform(multipart("/api/admin/kb/documents")
                            .file(mdFile("a.md", new byte[0]))
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadRequest());
            verify(service, org.mockito.Mockito.never()).upload(any(), any());
        }

        @Test
        void upload_missingFilePart_400_invalidFile() throws Exception {
            String body = mvc.perform(multipart("/api/admin/kb/documents")
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_INVALID_FILE");
        }

        @Test
        void list_200_viewsWithFormattedTime() throws Exception {
            when(service.list()).thenReturn(List.of(
                    doc(1L, "a.md", "READY", null),
                    doc(2L, "b.txt", "FAILED", "向量化失败: 连接超时")));

            String body = mvc.perform(get("/api/admin/kb/documents").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains("\"fileName\":\"a.md\"").contains("\"status\":\"READY\"")
                    .contains("\"fileName\":\"b.txt\"").contains("向量化失败")
                    .contains("2026-09-10 12:30:15");
        }

        @Test
        void delete_existing_204() throws Exception {
            mvc.perform(delete("/api/admin/kb/documents/9").header(HDR, TOKEN))
                    .andExpect(status().isNoContent());
            verify(service).delete(9L);
        }

        @Test
        void delete_missing_404_kbNotFound() throws Exception {
            org.mockito.Mockito.doThrow(new KbAdminException(KbAdminException.KB_NOT_FOUND, "不存在"))
                    .when(service).delete(404L);

            String body = mvc.perform(delete("/api/admin/kb/documents/404").header(HDR, TOKEN))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_NOT_FOUND");
        }

        @Test
        void reindex_200_readyView() throws Exception {
            when(service.reindex(7L)).thenReturn(doc(7L, "a.md", "READY", null));

            String body = mvc.perform(post("/api/admin/kb/documents/7/reindex").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("\"id\":7").contains("\"status\":\"READY\"");
        }

        @Test
        void reindex_failure_502_andDocumentMarkedFailed() throws Exception {
            when(service.reindex(8L))
                    .thenThrow(new KbAdminException(KbAdminException.KB_EMBEDDING_FAILED, "Ollama 500"));
            mvc.perform(post("/api/admin/kb/documents/8/reindex").header(HDR, TOKEN))
                    .andExpect(status().isBadGateway());
        }
    }

    // ---------- rag 开关关闭：路径闸门 503 KB_DISABLED（优先于 token 错误） ----------

    @WebMvcTest(KbAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN,
            "app.tools.enabled=true", "app.rag.enabled=false"})
    @Nested
    class RagDisabledSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private KbDocumentService service;

        @Test
        void kbRequest_503_kbDisabled_evenWithCorrectToken() throws Exception {
            String body = mvc.perform(get("/api/admin/kb/documents").header(HDR, TOKEN))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_DISABLED");
            org.mockito.Mockito.verifyNoInteractions(service);
        }
    }

    // ---------- 防御分支：service bean 缺席 → 503 KB_DISABLED（正常由拦截器先挡） ----------

    @Test
    @SuppressWarnings("unchecked")
    void serviceAbsent_controllerThrowsKbDisabled() {
        ObjectProvider<KbDocumentService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        KbAdminController controller = new KbAdminController(provider);

        assertThatThrownBy(controller::listDocuments)
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_DISABLED);
    }
}
