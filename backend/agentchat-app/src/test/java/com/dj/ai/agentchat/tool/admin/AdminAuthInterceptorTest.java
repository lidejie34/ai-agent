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
 * T0：管理端鉴权拦截器三层语义（AC-49/50/51/52）——
 * 503 ADMIN_NOT_CONFIGURED（token 未配置，优先级最高）→ 400 TOOLS_DISABLED（开关关闭）
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

        @GetMapping("/api/chat/ping")
        String chat() {
            return "ok-chat";
        }
    }

    private MockMvc mvc(AdminProperties props, boolean toolsEnabled) {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(props, toolsEnabled);
        return MockMvcBuilders.standaloneSetup(new AdminTestController())
                // 与生产 ToolAdminWebConfig 一致：仅拦 /api/admin/**
                .addMappedInterceptors(new String[]{"/api/admin/**"}, interceptor)
                .build();
    }

    private static AdminProperties props(String token) {
        AdminProperties p = new AdminProperties();
        p.setToken(token);
        return p;
    }

    @Test
    void tokenNotConfigured_returns503_adminNotConfigured() throws Exception {
        String body = mvc(props("  "), true).perform(get("/api/admin/tools"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("ADMIN_NOT_CONFIGURED").contains("管理端未配置访问令牌");
    }

    @Test
    void tokenNotConfigured_takesPrecedence_overDisabledSwitch() throws Exception {
        // 503 优先于 400：未配置 token 时即使开关关闭也报 503
        mvc(props(""), false).perform(get("/api/admin/tools"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void toolsDisabled_returns400_toolsDisabled() throws Exception {
        String body = mvc(props(TOKEN), false).perform(get("/api/admin/tools"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("TOOLS_DISABLED").contains("工具功能未启用");
    }

    @Test
    void missingTokenHeader_returns401() throws Exception {
        String body = mvc(props(TOKEN), true).perform(get("/api/admin/tools"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("ADMIN_UNAUTHORIZED");
    }

    @Test
    void wrongToken_returns401() throws Exception {
        mvc(props(TOKEN), true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctToken_passesThrough() throws Exception {
        String body = mvc(props(TOKEN), true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("ok-tools");
    }

    @Test
    void chatPath_isNotIntercepted_evenWithoutTokenConfigured() throws Exception {
        // /api/chat/** 不匹配拦截路径模式：管理端未配置 token 也不影响聊天接口（AC-51）
        String body = mvc(props(""), true).perform(get("/api/chat/ping"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("ok-chat");
    }

    @Test
    void errorBody_isStructuredJson_withoutTokenLeak() throws Exception {
        String body = mvc(props(TOKEN), true).perform(get("/api/admin/tools")
                        .header(AdminAuthInterceptor.ADMIN_TOKEN_HEADER, "guess"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\"").contains("\"message\"").contains("\"timestamp\"");
        assertThat(body).doesNotContain(TOKEN);
    }
}
