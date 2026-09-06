package com.dj.ai.agentchat.controller;

import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.ToolNotFoundException;
import com.dj.ai.agentchat.exception.ToolsUnavailableException;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import com.dj.ai.agentchat.tool.admin.AdminToolController;
import com.dj.ai.agentchat.tool.admin.ToolAdminService;
import com.dj.ai.agentchat.tool.admin.dto.PageResult;
import com.dj.ai.agentchat.tool.admin.dto.ToolCallLogView;
import com.dj.ai.agentchat.tool.admin.dto.ToolDetail;
import com.dj.ai.agentchat.tool.admin.dto.ToolListItem;
import com.dj.ai.agentchat.tool.admin.dto.ToolUpsertRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10：管理端接口切片——鉴权矩阵（拦截器三语义 503/400/401/200）、CRUD 状态码与
 * fastjson2 线格式（无引号包裹/null 省略/嵌套 JSON 对象）、异常映射（404/503/400）、
 * 分页参数解析。生产装配同构：ToolAdminWebConfig 的拦截器随 WebMvcConfigurer 进切片，
 * GlobalExceptionHandler 随 @ControllerAdvice 进切片，JSON 走 @Import fastjson2 转换器。
 */
class AdminToolControllerTest {

    private static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    // ---------- 1) 正常配置切片：200/401/400/404/503 业务矩阵 ----------

    @WebMvcTest(AdminToolController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN, "app.tools.enabled=true"})
    @Nested
    class AdminSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private ToolAdminService service;

        @Test
        void missingTokenHeader_401() throws Exception {
            mvc.perform(get("/api/admin/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void wrongToken_401_andTokenNotLeaked() throws Exception {
            String body = mvc.perform(get("/api/admin/tools").header(HDR, "guess"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("ADMIN_UNAUTHORIZED").doesNotContain(TOKEN);
        }

        @Test
        void correctToken_200() throws Exception {
            when(service.listTools()).thenReturn(List.of());
            mvc.perform(get("/api/admin/tools").header(HDR, TOKEN)).andExpect(status().isOk());
        }

        @Test
        void list_returnsItems_withGuideLength_withoutGuideBody() throws Exception {
            when(service.listTools()).thenReturn(List.of(
                    new ToolListItem(1L, "analyze_log", "分析日志", "BUILTIN", true, 30000, 8000, 128,
                            null, null),
                    new ToolListItem(2L, "log_error_count", "脚本工具", "SCRIPT", false, 30000, 8000, 0,
                            null, null)));

            String body = mvc.perform(get("/api/admin/tools").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // fastjson2 线格式：数组、嵌套字段、null 省略；列表无 guideMd 全文
            assertThat(body).contains("\"guideLength\":128").contains("\"guideLength\":0")
                    .contains("\"enabled\":false").doesNotContain("guideMd");
        }

        @Test
        void detail_notFound_404_toolNotFound() throws Exception {
            when(service.getTool(99L)).thenThrow(new ToolNotFoundException("工具不存在: id=99"));
            String body = mvc.perform(get("/api/admin/tools/99").header(HDR, TOKEN))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("TOOL_NOT_FOUND").contains("id=99");
        }

        @Test
        void detail_found_nestedJsonObject_notEscapedString() throws Exception {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            JSONObject config = new JSONObject();
            config.put("bean", "analyzeLogErrors");
            when(service.getTool(7L)).thenReturn(new ToolDetail(7L, "analyze_log", "描述", schema,
                    "BUILTIN", config, "指南全文", true, 30000, 8000, null, null));

            String body = mvc.perform(get("/api/admin/tools/7").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // inputSchema 是嵌套 JSON 对象（不是转义字符串），guide 全文仅详情含
            assertThat(body).contains("\"inputSchema\":{\"type\":\"object\"}")
                    .contains("\"handlerConfig\":{\"bean\":\"analyzeLogErrors\"}")
                    .contains("指南全文").doesNotContain("\\\"type\\\"");
        }

        @Test
        void nonNumericId_400() throws Exception {
            mvc.perform(get("/api/admin/tools/abc").header(HDR, TOKEN))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void post_valid_201_andRequestBoundAsJsonObjects() throws Exception {
            when(service.createTool(any())).thenAnswer(inv -> {
                ToolUpsertRequest req = inv.getArgument(0);
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                JSONObject config = new JSONObject();
                config.put("bean", req.handlerConfig().getString("bean"));
                return new ToolDetail(11L, req.name(), req.description(), schema,
                        req.handlerType(), config, null, true, 30000, 8000, null, null);
            });

            String requestBody = """
                    {"name":"analyze_log","description":"分析日志错误",
                     "inputSchema":{"type":"object","properties":{"minutes":{"type":"integer"}}},
                     "handlerType":"BUILTIN","handlerConfig":{"bean":"analyzeLogErrors"}}
                    """;
            String body = mvc.perform(post("/api/admin/tools").header(HDR, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            ArgumentCaptor<ToolUpsertRequest> captor = ArgumentCaptor.forClass(ToolUpsertRequest.class);
            verify(service).createTool(captor.capture());
            assertThat(captor.getValue().name()).isEqualTo("analyze_log");
            assertThat(captor.getValue().inputSchema().getJSONObject("properties").containsKey("minutes")).isTrue();
            assertThat(body).contains("\"name\":\"analyze_log\"").contains("\"id\":11");
        }

        @Test
        void post_validationError_400_badRequest_withFieldName() throws Exception {
            when(service.createTool(any()))
                    .thenThrow(new InvalidChatRequestException("工具名(name)格式非法"));
            String body = mvc.perform(post("/api/admin/tools").header(HDR, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("BAD_REQUEST").contains("name");
        }

        @Test
        void put_nameChange_400() throws Exception {
            when(service.updateTool(eq(1L), any(), eq(false)))
                    .thenThrow(new InvalidChatRequestException("工具名(name)不可修改"));
            mvc.perform(put("/api/admin/tools/1").header(HDR, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"new_name\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void patch_enabledFalse_200_partialTrue() throws Exception {
            when(service.updateTool(eq(1L), any(), eq(true))).thenAnswer(inv ->
                    new ToolDetail(1L, "analyze_log", "d", null, "BUILTIN", null, null, false,
                            30000, 8000, null, null));

            String body = mvc.perform(patch("/api/admin/tools/1").header(HDR, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            verify(service).updateTool(eq(1L), any(), eq(true));
            assertThat(body).contains("\"enabled\":false");
        }

        @Test
        void delete_existing_200() throws Exception {
            when(service.deleteTool(5L)).thenReturn(
                    new ToolDetail(5L, "old_tool", "d", null, "BUILTIN", null, null, true,
                            30000, 8000, null, null));
            String body = mvc.perform(delete("/api/admin/tools/5").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("\"name\":\"old_tool\"");
        }

        @Test
        void delete_notFound_404() throws Exception {
            when(service.deleteTool(404L)).thenThrow(new ToolNotFoundException("工具不存在: id=404"));
            mvc.perform(delete("/api/admin/tools/404").header(HDR, TOKEN))
                    .andExpect(status().isNotFound());
        }

        @Test
        void logs_defaultPaging_200_pageResult() throws Exception {
            when(service.pageLogs(anyInt(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                Integer page = inv.getArgument(0);
                Integer size = inv.getArgument(1);
                return new PageResult<>(List.of(new ToolCallLogView(1L, "req-1|t|a", "t", "BUILTIN",
                        null, "{}", "SUCCESS", 10L, null, 100, LocalDateTime.now())), 1L, page, size);
            });

            String body = mvc.perform(get("/api/admin/tool-call-logs").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            verify(service).pageLogs(eq(0), eq(20), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
            assertThat(body).contains("\"total\":1").contains("\"page\":0").contains("\"size\":20")
                    .contains("\"toolName\":\"t\"");
        }

        @Test
        void logs_badTimeFormat_400_withFieldName() throws Exception {
            String body = mvc.perform(get("/api/admin/tool-call-logs")
                            .header(HDR, TOKEN).param("from", "2026/09/04"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("from").contains("BAD_REQUEST");
        }

        @Test
        void logs_badSize_400() throws Exception {
            mvc.perform(get("/api/admin/tool-call-logs").header(HDR, TOKEN).param("size", "abc"))
                    .andExpect(status().isBadRequest());
            verify(service, org.mockito.Mockito.never())
                    .pageLogs(anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void logs_isoTimeAccepted() throws Exception {
            when(service.pageLogs(anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(), 0L, 0, 20));
            mvc.perform(get("/api/admin/tool-call-logs").header(HDR, TOKEN)
                            .param("from", "2026-09-04T10:00:00")
                            .param("to", "2026-09-04 12:00:00")
                            .param("toolName", "analyze_log").param("status", "FAILED"))
                    .andExpect(status().isOk());
        }

        /** 迭代4 AC-34：handlerType 参数透传（小写 mcp 由 service 归一化，白名单在 service 测）。 */
        @Test
        void logs_handlerTypeParam_passedThrough() throws Exception {
            when(service.pageLogs(anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(), 0L, 0, 20));

            mvc.perform(get("/api/admin/tool-call-logs").header(HDR, TOKEN)
                            .param("handlerType", "MCP"))
                    .andExpect(status().isOk());

            verify(service).pageLogs(eq(0), eq(20), eq(null), eq(null), eq(null),
                    eq("MCP"), eq(null), eq(null));
        }

        @Test
        void dbFailure_503_toolsUnavailable() throws Exception {
            when(service.listTools()).thenThrow(new ToolsUnavailableException("工具服务暂不可用（数据库访问失败）"));
            String body = mvc.perform(get("/api/admin/tools").header(HDR, TOKEN))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("TOOLS_UNAVAILABLE");
            // 不外泄 SQL/连接细节
            assertThat(body).doesNotContain("SQLException").doesNotContain("jdbc");
        }
    }

    // ---------- 2) token 未配置切片：拦截器 503（优先级最高） ----------

    @WebMvcTest(AdminToolController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=", "app.tools.enabled=true"})
    @Nested
    class TokenNotConfiguredSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private ToolAdminService service;

        @Test
        void adminRequest_503_adminNotConfigured() throws Exception {
            String body = mvc.perform(get("/api/admin/tools"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("ADMIN_NOT_CONFIGURED");
        }
    }

    // ---------- 3) 工具开关关闭切片：拦截器 400 TOOLS_DISABLED（管理端不 404） ----------

    @WebMvcTest(AdminToolController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN, "app.tools.enabled=false"})
    @Nested
    class ToolsDisabledSlice {

        @Autowired
        private MockMvc mvc;

        @Test
        void adminRequest_400_toolsDisabled() throws Exception {
            String body = mvc.perform(get("/api/admin/tools").header(HDR, TOKEN))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("TOOLS_DISABLED");
        }
    }

    // ---------- 4) 防御分支：service bean 缺席（ObjectProvider null）→ 400 ----------

    @Test
    @SuppressWarnings("unchecked")
    void serviceAbsent_controllerRaisesInvalidRequest() {
        ObjectProvider<ToolAdminService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AdminToolController controller = new AdminToolController(provider);
        assertThatThrownBy(controller::listTools)
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("工具功能未启用");
    }
}
