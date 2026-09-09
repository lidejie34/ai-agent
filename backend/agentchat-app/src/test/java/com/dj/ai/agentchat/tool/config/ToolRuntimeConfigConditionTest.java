package com.dj.ai.agentchat.tool.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dj.ai.agentchat.tool.AdminProperties;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0：工具开关条件装配（AC-1/2/4）——复刻 ChatMemoryConfigTest 双切片范式：
 * 默认（matchIfMissing=true）ToolProperties 装配、缺省值正确；
 * enabled=false 时工具运行时配置收口（ToolProperties/ToolRuntimeConfig 缺席）；
 * 管理端 web 层（AdminProperties/拦截器/ToolAdminWebConfig）无条件常驻。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class ToolRuntimeConfigConditionTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void toolProperties_arePresent_byDefault_withSafeDefaults() {
        ToolProperties props = context.getBean(ToolProperties.class);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getExecutorPoolSize()).isEqualTo(4);
        assertThat(props.getDefaultTimeoutMs()).isEqualTo(30000);
        assertThat(props.getDefaultOutputMaxChars()).isEqualTo(8000);
        assertThat(props.getScriptDir()).isEqualTo("scripts");
        assertThat(props.getRedactPatterns()).isEmpty();
        assertThat(props.getBuiltin().getLogDir()).isEqualTo("logs");
        assertThat(props.getBuiltin().getScanMaxFiles()).isEqualTo(200);
        assertThat(props.getBuiltin().getScanMaxBytesPerFile()).isEqualTo(52428800L);
        assertThat(props.getBuiltin().getScanMaxLines()).isEqualTo(200000);
    }

    @Test
    void adminWebLayer_isPresent_byDefault_withBlankToken() {
        AdminProperties props = context.getBean(AdminProperties.class);
        assertThat(props.getToken()).isBlank();
        // 常驻 web 层：拦截器/配置 bean 无条件装配
        assertThat(context.getBeansOfType(AdminAuthInterceptor.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ToolAdminWebConfig.class)).isNotEmpty();
    }

    @Test
    void mybatisPlusInterceptor_isRegistered_withPagination() {
        // 迭代 H 冒烟修复：缺分页拦截器时 selectPage 退化为全量且 total=0（审计分页失效）
        MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
        assertThat(interceptor.getInterceptors())
                .anySatisfy(inner -> assertThat(inner).isInstanceOf(PaginationInnerInterceptor.class));
    }
}

/**
 * app.tools.enabled=false：工具运行时全家桶不装配，应用上下文照常刷新；
 * 管理端 web 层常驻（开关关闭时管理端 400 而非 404，AC-2）。
 */
@SpringBootTest(properties = {
        "app.tools.enabled=false",
        "spring.ai.openai.api-key=ark-context-test-key"
})
class ToolRuntimeDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void toolRuntimeBeans_areAbsent_whenDisabled() {
        assertThat(context.getBeansOfType(ToolProperties.class)).isEmpty();
        assertThat(context.getBeansOfType(ToolRuntimeConfig.class)).isEmpty();
    }

    @Test
    void adminWebLayer_remainsPresent_whenDisabled() {
        assertThat(context.getBeansOfType(AdminProperties.class)).isNotEmpty();
        assertThat(context.getBeansOfType(AdminAuthInterceptor.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ToolAdminWebConfig.class)).isNotEmpty();
    }
}

/**
 * 配置绑定：app.tools.* / app.admin.token 外置覆盖。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=ark-context-test-key",
        "app.tools.executor-pool-size=7",
        "app.tools.script-dir=/opt/dj/scripts",
        "app.tools.builtin.log-dir=/var/log/app",
        "app.tools.builtin.scan-max-files=321",
        "app.admin.token=env-injected-token"
})
class ToolPropertiesBindingTest {

    @Autowired
    private ToolProperties toolProperties;

    @Autowired
    private AdminProperties adminProperties;

    @Test
    void toolAndAdminProperties_bindFromConfig() {
        assertThat(toolProperties.getExecutorPoolSize()).isEqualTo(7);
        assertThat(toolProperties.getScriptDir()).isEqualTo("/opt/dj/scripts");
        assertThat(toolProperties.getBuiltin().getLogDir()).isEqualTo("/var/log/app");
        assertThat(toolProperties.getBuiltin().getScanMaxFiles()).isEqualTo(321);
        assertThat(adminProperties.getToken()).isEqualTo("env-injected-token");
    }
}
