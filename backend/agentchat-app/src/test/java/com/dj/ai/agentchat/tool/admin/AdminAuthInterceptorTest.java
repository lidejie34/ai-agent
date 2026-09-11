package com.dj.ai.agentchat.tool.admin;

import com.dj.ai.agentchat.tool.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0：管理端鉴权拦截器（AC-49/50/51/52 + 迭代6 路径分派）——
 * 503 ADMIN_NOT_CONFIGURED（token 未配置，优先级最高）→ 路径闸门：
 * tools 开关关 → 400 TOOLS_DISABLED，rag 开关关 → 503 KB_DISABLED，其余管理端路径仅查 token
 * → 401 ADMIN_UNAUTHORIZED（缺头/错 token）→ 放行；非 /api/admin/** 路径不拦截。
 */
class AdminAuthInterceptorTest {

    private static final String TOKEN = "secret-admin-token";

    @RestController
    static class AdminTestController {

        @GetMapping("/api/admin/tools")
        String tools() {
            return "ok-tools";
        }

        @GetMapping("/api/admin/mcp/servers")
        String mcp() {
            return "ok-mcp";
        }

        @GetMapping("/api/admin/tool-call-logs")
        String logs() {
            return "ok-logs";
        }

        @GetMapping("/api/admin/kb/documents")
        String kb() {
            return "ok-kb";
        }

        @GetMapping("/api/admin/other")
        String other() {
            return "ok-other";
        }

        @GetMapping("/api/chat/ping")
        String chat() {
            return "ok-chat";
        }
    }

    private MockMvc mvc(AdminProperties props, boolean toolsEnabled, boolean ragEnabled) {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(props, toolsEnabled, ragEnabled);
        return MockMvcBuilders.standaloneSetup(new AdminTestController())
                // 与生产 ToolAdminWebConfig 一致：仅拦 /api/admin/**
                .addMappedInterceptors(new String[]{"/api/admin/**"}, interceptor)
                .build();
    }

    private MockMvc mvc(AdminProperties props, boolean toolsEnabled) {
        return mvc(props, toolsEnabled, true);
    }

    private static AdminProperties props(String token) {
        AdminProperties p = new AdminProperties();
        p.setToken(token);
        return p;
    }

    @Test
    void tokenNotConfigured_returns503_adminNotConfigured() throws Exception {
        String body = mvc(props("  "), true, true).perform(get("/api/admin/tools"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("ADMIN_NOT_CONFIGURED").contains("管理端未配置访问令牌");
    }

    @Test
    void tokenNotConfigured_takesPrecedence_overDisabledSwitches() throws Exception {
        // 503 优先于闸门：未配置 token 时即使两个开关都关闭也报 ADMIN_NOT_CONFIGURED
        mvc(props(""), false, false).perform(get("/api/admin/kb/documents"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void toolsDisabled_returns400_toolsDisabled() throws Exception {
        String body = mvc(props(TOKEN), false, true).perform(get("/api/admin/tools"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("TOOLS_DISABLED").contains("工具功能未启用");
    }

    @Test
    void toolsDisabled_blocksMcpAndCallLogs_butNotKb() throws Exception {
        mvc(props(TOKEN), false, true).perform(get("/api/admin/mcp/servers"))
                .andExpect(status().isBadRequest());
        mvc(props(TOKEN), false, true).perform(get("/api/admin/tool-call-logs"))
                .andExpect(status().isBadRequest());
        // 工具开关关闭不连累知识库路径（rag=true 且 token 正确 → 放行）
        mvc(props(TOKEN), false, true)
                .perform(get("/api/admin/kb/documents")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void ragDisabled_returns503_kbDisabled() throws Exception {
        String body = mvc(props(TOKEN), true, false).perform(get("/api/admin/kb/documents"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("KB_DISABLED").contains("知识库功能未启用");
    }

    @Test
    void ragDisabled_doesNotBlockToolsPaths() throws Exception {
        // 知识库开关关闭不连累工具路径（tools=true 且 token 正确 → 放行）
        mvc(props(TOKEN), true, false)
                .perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void otherAdminPath_isTokenOnly_passesWhenBothSwitchesOff() throws Exception {
        // 未来其他管理端路径只做 token 校验，两个功能开关都关也放行
        String body = mvc(props(TOKEN), false, false)
                .perform(get("/api/admin/other")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("ok-other");
    }

    @Test
    void missingTokenHeader_returns401() throws Exception {
        String body = mvc(props(TOKEN), true, true).perform(get("/api/admin/tools"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("ADMIN_UNAUTHORIZED");
    }

    @Test
    void wrongToken_returns401() throws Exception {
        mvc(props(TOKEN), true, true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctToken_passesThrough() throws Exception {
        String body = mvc(props(TOKEN), true, true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("ok-tools");
    }

    @Test
    void chatPath_isNotIntercepted_evenWithoutTokenConfigured() throws Exception {
        // /api/chat/** 不匹配拦截路径模式：管理端未配置 token 也不影响聊天接口（AC-51）
        String body = mvc(props(""), true, true).perform(get("/api/chat/ping"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("ok-chat");
    }

    @Test
    void errorBody_isStructuredJson_withoutTokenLeak() throws Exception {
        String body = mvc(props(TOKEN), true, true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, "guess"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\"").contains("\"message\"").contains("\"timestamp\"");
        assertThat(body).doesNotContain(TOKEN);
    }
}
