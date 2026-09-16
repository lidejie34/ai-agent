package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代10 迁移：DimTagAdminController 切片（原 DimAdminControllerTest 的标签半边）——
 * 标签列表/改名/删除透传、鉴权拦截（无 token 401）。
 */
@WebMvcTest(DimTagAdminController.class)
@Import(FastJsonWebConfig.class)
@TestPropertySource(properties = {"app.admin.token=" + DimTagAdminControllerTest.TOKEN,
        "app.tools.enabled=true", "app.rag.enabled=true"})
class DimTagAdminControllerTest {

    static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DimTagService service;

    @Test
    void listTags_returnsViews() throws Exception {
        when(service.listTags()).thenReturn(List.of(new DimTagView("售后", 5)));

        mvc.perform(get("/api/admin/dim/tags").header(HDR, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("售后"))
                .andExpect(jsonPath("$[0].docCount").value(5));
    }

    @Test
    void renameTag_happy_returnsAffectedDocs() throws Exception {
        when(service.renameTag("退货", "换货")).thenReturn(2);

        mvc.perform(patch("/api/admin/dim/tags")
                        .header(HDR, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"退货\",\"to\":\"换货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedDocs").value(2));
    }

    @Test
    void deleteTag_happy_returnsAffectedDocs() throws Exception {
        when(service.deleteTag("退货")).thenReturn(1);

        mvc.perform(delete("/api/admin/dim/tags/退货").header(HDR, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedDocs").value(1));
    }

    @Test
    void missingToken_rejected401() throws Exception {
        mvc.perform(get("/api/admin/dim/tags"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }
}
