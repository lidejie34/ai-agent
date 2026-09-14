package com.dj.ai.agentchat.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代9 R-2 专项：引入 actuator 依赖后默认暴露面不扩大。
 *
 * <p>构建期实证（清单 #4 核销，两处与设计预期不符、以代码实际为准）：
 * <ol>
 *   <li>{@code exposure.include} 空串会被绑定为「未设置」而回落 Boot 默认
 *       （web=health、jmx=*）——application.yml 默认收窄改用不存在的占位端点 id
 *       {@code observability-none}；</li>
 *   <li>/actuator 根链接页（discovery）不受白名单收口——application.yml 默认
 *       {@code discovery.enabled=false}；</li>
 *   <li>本应用未知路径语义并非 404：{@code NoResourceFoundException} 经
 *       GlobalExceptionHandler 归一为 500 INTERNAL_ERROR（/no-such-path 同构）。
 *       故「与引入 actuator 前一致」的断言口径 = 与未知路径响应同构。
 * </ol>
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
@AutoConfigureMockMvc
class ActuatorExposureDisabledByDefaultTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorEndpoints_behaveLikeUnknownPaths_byDefault() throws Exception {
        for (String url : new String[]{"/actuator", "/actuator/health",
                "/actuator/metrics", "/actuator/slowrequests"}) {
            mockMvc.perform(get(url))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        }
        // 与未知路径响应同构（除 timestamp 外 code/message 完全一致）
        MvcResult unknown = mockMvc.perform(get("/no-such-path")).andReturn();
        MvcResult actuator = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(actuator.getResponse().getStatus())
                .isEqualTo(unknown.getResponse().getStatus());
        assertThat(actuator.getResponse().getContentAsString())
                .contains("\"code\":\"INTERNAL_ERROR\"")
                .contains("\"message\":\"服务器内部错误，请稍后重试\"");
    }
}

/**
 * 双保险验证：可观测性总开关开启（SlowRequestsEndpoint bean 在场）但
 * exposure 白名单未放开时，自定义端点同样走未知路径语义——
 * 暴露面由白名单独立收口（bean 存在 ≠ 端点可见）。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.observability.enabled=true"
})
@AutoConfigureMockMvc
class ActuatorExposureDisabledWhenObservabilityEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void customEndpoint_behavesLikeUnknownPath_whenNotExposed_evenIfBeanPresent() throws Exception {
        for (String url : new String[]{"/actuator/slowrequests", "/actuator/metrics",
                "/actuator/health", "/actuator"}) {
            mockMvc.perform(get(url))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        }
    }
}
