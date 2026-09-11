package com.dj.ai.agentchat.rag.admin;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.rag.admin.dto.KbHealthView;
import com.dj.ai.agentchat.rag.admin.service.KbHealthService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10（迭代6）：KbHealthController 切片——GET /api/admin/kb/health 200 视图字段；
 * 缺 token 401；rag 关 → 路径闸门 503 KB_DISABLED；service bean 缺席防御性返回 enabled=false。
 */
class KbHealthControllerTest {

    private static final String TOKEN = "test-admin-token";
    private static final String HDR = AdminAuthInterceptor.ADMIN_TOKEN_HEADER;

    @WebMvcTest(KbHealthController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN,
            "app.tools.enabled=true", "app.rag.enabled=true"})
    @Nested
    class KbHealthSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private KbHealthService healthService;

        @Test
        void missingToken_401() throws Exception {
            mvc.perform(get("/api/admin/kb/health")).andExpect(status().isUnauthorized());
        }

        @Test
        void health_200_allFields() throws Exception {
            when(healthService.health())
                    .thenReturn(new KbHealthView(true, true, true, 3L, 27L, 1024));

            String body = mvc.perform(get("/api/admin/kb/health").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains("\"enabled\":true").contains("\"ollamaOk\":true")
                    .contains("\"pgOk\":true").contains("\"documentCount\":3")
                    .contains("\"chunkCount\":27").contains("\"dimensions\":1024");
        }

        @Test
        void health_200_degradedStateOllamaDown() throws Exception {
            when(healthService.health())
                    .thenReturn(new KbHealthView(true, false, true, 1L, 5L, 1024));

            String body = mvc.perform(get("/api/admin/kb/health").header(HDR, TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains("\"ollamaOk\":false").contains("\"pgOk\":true")
                    .contains("\"documentCount\":1");
        }
    }

    // ---------- rag 开关关闭：路径闸门 503 KB_DISABLED ----------

    @WebMvcTest(KbHealthController.class)
    @Import(FastJsonWebConfig.class)
    @TestPropertySource(properties = {"app.admin.token=" + TOKEN,
            "app.tools.enabled=true", "app.rag.enabled=false"})
    @Nested
    class RagDisabledSlice {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private KbHealthService healthService;

        @Test
        void health_503_kbDisabled_evenWithCorrectToken() throws Exception {
            String body = mvc.perform(get("/api/admin/kb/health").header(HDR, TOKEN))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("KB_DISABLED");
        }
    }

    // ---------- 防御分支：service bean 缺席 → enabled=false 兜底（正常由拦截器先挡） ----------

    @Test
    @SuppressWarnings("unchecked")
    void serviceAbsent_returnsDisabledView() {
        ObjectProvider<KbHealthService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        KbHealthController controller = new KbHealthController(provider);

        KbHealthView view = controller.health();

        assertThat(view.enabled()).isFalse();
        assertThat(view.ollamaOk()).isFalse();
        assertThat(view.pgOk()).isFalse();
        assertThat(view.documentCount()).isZero();
        assertThat(view.chunkCount()).isZero();
        assertThat(view.dimensions()).isEqualTo(1024);
    }
}
