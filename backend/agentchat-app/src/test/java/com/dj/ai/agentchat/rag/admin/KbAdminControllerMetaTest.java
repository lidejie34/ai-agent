package com.dj.ai.agentchat.rag.admin;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.rag.admin.service.KbDocumentService;
import com.dj.ai.agentchat.rag.store.RagDocument;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 迭代10：KbAdminController 维度元数据切片——上传表单 project/tags 透传、
 * PATCH 全量替换（200/404/非法 400）、列表 project/tag 过滤透传、
 * 视图线格式含 project/tags 两字段（additive）。
 */
class KbAdminControllerMetaTest {

    private static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    private static RagDocument docWithMeta(long id, String name, String project, List<String> tags) {
        return new RagDocument(id, name, 128, "原文", "hash", 3, "READY", null,
                LocalDateTime.of(2026, 9, 15, 10, 0, 0),
                LocalDateTime.of(2026, 9, 15, 10, 1, 0),
                project, tags);
    }

    @WebMvcTest(KbAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN,
            "app.tools.enabled=true", "app.rag.enabled=true"})
    @Nested
    class MetaSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private KbDocumentService service;

        @Test
        void upload_withMetaFormFields_passesNormalizedToService() throws Exception {
            when(service.upload(eq("售后.md"), eq("内容".getBytes(StandardCharsets.UTF_8)),
                    eq("订单域"), eq(List.of("售后", "退货"))))
                    .thenReturn(docWithMeta(7L, "售后.md", "订单域", List.of("售后", "退货")));

            MockMultipartFile file = new MockMultipartFile("file", "售后.md", "text/markdown",
                    "内容".getBytes(StandardCharsets.UTF_8));
            mvc.perform(MockMvcRequestBuilders.multipart("/api/admin/kb/documents")
                            .file(file)
                            .param("project", "订单域")
                            .param("tags", "售后, 退货")
                            .header(HDR, TOKEN))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.project").value("订单域"))
                    .andExpect(jsonPath("$.tags[0]").value("售后"))
                    .andExpect(jsonPath("$.tags[1]").value("退货"));
        }

        @Test
        void upload_withoutMeta_passesNullAndEmptyList() throws Exception {
            when(service.upload(eq("a.md"), org.mockito.ArgumentMatchers.any(byte[].class),
                    isNull(), eq(List.of())))
                    .thenReturn(docWithMeta(8L, "a.md", null, List.of()));

            MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown",
                    "内容".getBytes(StandardCharsets.UTF_8));
            mvc.perform(MockMvcRequestBuilders.multipart("/api/admin/kb/documents")
                            .file(file).header(HDR, TOKEN))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.project").doesNotExist())
                    .andExpect(jsonPath("$.tags").isArray());
        }

        @Test
        void patchMeta_fullReplace_200() throws Exception {
            when(service.updateMeta(7L, "物流域", List.of("承运")))
                    .thenReturn(docWithMeta(7L, "a.md", "物流域", List.of("承运")));

            mvc.perform(MockMvcRequestBuilders.patch("/api/admin/kb/documents/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"project\":\"物流域\",\"tags\":[\"承运\"]}")
                            .header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.project").value("物流域"))
                    .andExpect(jsonPath("$.tags[0]").value("承运"));
        }

        @Test
        void patchMeta_clearProject_nullPassed() throws Exception {
            when(service.updateMeta(eq(7L), isNull(), eq(List.of())))
                    .thenReturn(docWithMeta(7L, "a.md", null, List.of()));

            mvc.perform(MockMvcRequestBuilders.patch("/api/admin/kb/documents/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"project\":null,\"tags\":[]}")
                            .header(HDR, TOKEN))
                    .andExpect(status().isOk());
            verify(service).updateMeta(7L, null, List.of());
        }

        @Test
        void patchMeta_notFound_404() throws Exception {
            when(service.updateMeta(eq(99L), isNull(), eq(List.of())))
                    .thenThrow(new KbAdminException(KbAdminException.KB_NOT_FOUND, "文档不存在: id=99"));

            mvc.perform(MockMvcRequestBuilders.patch("/api/admin/kb/documents/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"project\":null,\"tags\":[]}")
                            .header(HDR, TOKEN))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
        }

        @Test
        void patchMeta_invalidTags_400KbInvalidTags() throws Exception {
            when(service.updateMeta(eq(7L), isNull(), eq(List.of("a,b"))))
                    .thenThrow(new KbAdminException(KbAdminException.KB_INVALID_TAGS,
                            "标签仅支持中文/字母/数字/中划线/下划线，不支持逗号等特殊字符：「a,b」"));

            mvc.perform(MockMvcRequestBuilders.patch("/api/admin/kb/documents/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"project\":null,\"tags\":[\"a,b\"]}")
                            .header(HDR, TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("KB_INVALID_TAGS"));
        }

        @Test
        void list_withFilters_passesThrough() throws Exception {
            when(service.list("订单域", "售后"))
                    .thenReturn(List.of(docWithMeta(7L, "售后.md", "订单域", List.of("售后"))));

            mvc.perform(get("/api/admin/kb/documents?project=订单域&tag=售后")
                            .header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].project").value("订单域"));
            verify(service).list("订单域", "售后");
        }

        @Test
        void list_withoutFilters_passesNulls() throws Exception {
            when(service.list(isNull(), isNull())).thenReturn(List.of());

            mvc.perform(get("/api/admin/kb/documents").header(HDR, TOKEN))
                    .andExpect(status().isOk());
            verify(service).list(null, null);
        }
    }
}
