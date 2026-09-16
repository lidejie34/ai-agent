package com.dj.ai.agentchat.dim;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代10 迁移：DimProjectAdminController 切片（原 DimAdminControllerTest 的项目半边）——
 * 项目 CRUD（201/409/404/204）、鉴权拦截（无 token 401）。
 * 与标签端点差异：dim/projects 仅 TOKEN_ONLY 闸门，RAG 关闭也可达（gate 单测在
 * AdminGateResolverTest；此处 rag.enabled=false 验证控制器本身不依赖 rag bean）。
 */
@WebMvcTest(DimProjectAdminController.class)
@Import(FastJsonWebConfig.class)
@TestPropertySource(properties = {"app.admin.token=" + DimProjectAdminControllerTest.TOKEN,
        "app.tools.enabled=true", "app.rag.enabled=false"})
class DimProjectAdminControllerTest {

    static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DimProjectService service;

    private static DimProject project(long id, String name, long docCount) {
        return new DimProject(id, name, "备注", docCount,
                LocalDateTime.of(2026, 9, 15, 10, 0), LocalDateTime.of(2026, 9, 15, 10, 1));
    }

    @Test
    void listProjects_returnsViewsWithDocCount() throws Exception {
        when(service.listProjects()).thenReturn(List.of(project(1L, "订单域", 3)));

        mvc.perform(get("/api/admin/dim/projects").header(HDR, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("订单域"))
                .andExpect(jsonPath("$[0].docCount").value(3));
    }

    @Test
    void createProject_happy_returns201() throws Exception {
        when(service.createProject(eq("订单域"), isNull())).thenReturn(project(7L, "订单域", 0));

        mvc.perform(post("/api/admin/dim/projects")
                        .header(HDR, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"订单域\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("订单域"));
    }

    @Test
    void createProject_duplicate_returns409Code() throws Exception {
        when(service.createProject(eq("订单域"), isNull()))
                .thenThrow(new KbAdminException(KbAdminException.KB_PROJECT_EXISTS,
                        "项目已存在：订单域"));

        mvc.perform(post("/api/admin/dim/projects")
                        .header(HDR, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"订单域\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KB_PROJECT_EXISTS"));
    }

    @Test
    void updateProject_notFound_returns404() throws Exception {
        when(service.updateProject(eq(99L), eq("x"), isNull()))
                .thenThrow(new KbAdminException(KbAdminException.KB_PROJECT_NOT_FOUND,
                        "项目不存在: id=99"));

        mvc.perform(patch("/api/admin/dim/projects/99")
                        .header(HDR, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_PROJECT_NOT_FOUND"));
    }

    @Test
    void deleteProject_inUse_returns409WithMessage() throws Exception {
        org.mockito.Mockito.doThrow(new KbAdminException(KbAdminException.KB_PROJECT_IN_USE,
                        "项目「订单域」仍被 4 篇文档引用，请先调整文档归属后再删除"))
                .when(service).deleteProject(5L);

        mvc.perform(delete("/api/admin/dim/projects/5").header(HDR, TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KB_PROJECT_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("4 篇文档")));
    }

    @Test
    void deleteProject_happy_returns204() throws Exception {
        mvc.perform(delete("/api/admin/dim/projects/5").header(HDR, TOKEN))
                .andExpect(status().isNoContent());

        verify(service).deleteProject(5L);
    }

    @Test
    void missingToken_rejected401() throws Exception {
        mvc.perform(get("/api/admin/dim/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }
}
