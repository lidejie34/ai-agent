package com.dj.ai.agentchat.tool.config;

import com.dj.ai.agentchat.tool.AdminProperties;
import com.dj.ai.agentchat.tool.admin.AdminAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 工具管理端 web 装配（插入迭代 G）：<b>无条件常驻</b>——
 * 即使 {@code app.tools.enabled=false}（工具运行时整包不装配），管理端 Controller
 * 与鉴权拦截器仍在，请求被拦截器挡为 400 {@code TOOLS_DISABLED} 而非 404（AC-2）。
 *
 * <p>拦截范围仅 {@code /api/admin/**}；{@code /api/chat/**}、{@code /api/sessions/**}
 * 不匹配路径模式，零影响（AC-51）。
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class ToolAdminWebConfig implements WebMvcConfigurer {

    private final AdminProperties adminProperties;
    private final boolean toolsEnabled;

    public ToolAdminWebConfig(AdminProperties adminProperties,
                              @Value("${app.tools.enabled:true}") boolean toolsEnabled) {
        this.adminProperties = adminProperties;
        this.toolsEnabled = toolsEnabled;
    }

    @Bean
    public AdminAuthInterceptor adminAuthInterceptor() {
        return new AdminAuthInterceptor(adminProperties, toolsEnabled);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor())
                .addPathPatterns("/api/admin/**");
    }
}
