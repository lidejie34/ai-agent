package com.dj.ai.agentchat.tool.mcp.admin;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 管理端只读接口切片（迭代4 T5，AC-28/32/33/35）：
 * 鉴权矩阵复用 AdminAuthInterceptor（/api/admin/**）；200 视图不含 env；
 * 写方法 405；service bean 缺席 → {"servers":[]} 200（非 5xx）。
 */
class McpAdminControllerTest {

    private static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    private McpServerView readyView() {
        return new McpServerView("my-fs", "/opt/homebrew/bin/npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/data/mcp"),
                "READY", 2,
                List.of(new McpToolView("my_fs_read_file", "Read-File", "读取文件"),
                        new McpToolView("my_fs_write_file", "write_file", "写入文件")),
                null, LocalDateTime.now());
    }

    private McpServerView unavailableView() {
        return new McpServerView("ghost", "/usr/bin/node", List.of("srv.js"),
                "UNAVAILABLE", 0, List.of(), "握手失败: 启动即退", null);
    }

    // ---------- 1) 正常切片：鉴权 + 只读视图 + 405 ----------

    @WebMvcTest(McpAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN, "app.tools.enabled=true"})
    @Nested
    class McpAdminSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private AdminMcpService service;

        @Test
        void missingToken_401() throws Exception {
            mvc.perform(get("/api/admin/mcp/servers")).andExpect(status().isUnauthorized());
        }

        @Test
        void wrongToken_401() throws Exception {
            mvc.perform(get("/api/admin/mcp/servers").header(HDR, "guess"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void servers_200_fieldsComplete_andNoEnvLeak() throws Exception {
            when(service.listServers()).thenReturn(List.of(readyView(), unavailableView()));

            String body = mvc.perform(get("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains("\"servers\":[")
                    .contains("\"name\":\"my-fs\"").contains("\"status\":\"READY\"")
                    .contains("\"name\":\"ghost\"").contains("\"status\":\"UNAVAILABLE\"")
                    .contains("my_fs_read_file").contains("Read-File")
                    .contains("\"toolCount\":2").contains("握手失败")
                    .contains("/opt/homebrew/bin/npx").contains("server-filesystem");
            // 红线：响应绝不含 env 字段/值（AC-27/AC-32）
            assertThat(body).doesNotContain("\"env\"").doesNotContain("SECRET_ENV_VAL");
        }

        /** AC-28：不存在任何 MCP 配置写接口——POST/PUT/DELETE 落同路径 405。 */
        @Test
        void writeMethods_405() throws Exception {
            mvc.perform(post("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(put("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(delete("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // ---------- 2) service bean 缺席（mcp 子开关关闭）：空列表 200，不 5xx（AC-35） ----------

    @WebMvcTest(McpAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN, "app.tools.enabled=true"})
    @Nested
    class ServiceAbsentSlice {

        @Autowired
        private MockMvc mvc;

        @Test
        void servers_200_emptyListWhenServiceAbsent() throws Exception {
            String body = mvc.perform(get("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("\"servers\":[")
                    .doesNotContain("\"name\"");
        }
    }

    // ---------- 3) token 未配置：503（拦截器优先） ----------

    @WebMvcTest(McpAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=", "app.tools.enabled=true"})
    @Nested
    class TokenNotConfiguredSlice {

        @Autowired
        private MockMvc mvc;

        @Test
        void adminRequest_503_adminNotConfigured() throws Exception {
            String body = mvc.perform(get("/api/admin/mcp/servers"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("ADMIN_NOT_CONFIGURED");
        }
    }

    // ---------- 4) 工具总开关关闭：400 TOOLS_DISABLED ----------

    @WebMvcTest(McpAdminController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN, "app.tools.enabled=false"})
    @Nested
    class ToolsDisabledSlice {

        @Autowired
        private MockMvc mvc;

        @Test
        void adminRequest_400_toolsDisabled() throws Exception {
            String body = mvc.perform(get("/api/admin/mcp/servers").header(HDR, TOKEN))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("TOOLS_DISABLED");
        }
    }

    // ---------- 5) 防御分支：ObjectProvider 缺席 → 空包装 ----------

    @Test
    @SuppressWarnings("unchecked")
    void serviceAbsent_controllerReturnsEmptyServers() {
        ObjectProvider<AdminMcpService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        McpAdminController controller = new McpAdminController(provider);

        McpAdminController.McpServersResponse response = controller.servers();

        assertThat(response.servers()).isEmpty();
    }
}
