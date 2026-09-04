package com.dj.ai.agentchat.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端参数（插入迭代 G，前缀 {@code app.admin}）：常驻配置（不随工具开关收口），
 * 保证开关关闭时管理端返回 400 {@code TOOLS_DISABLED} 而非 404。
 *
 * <p>token 仅从配置 / 环境变量 {@code APP_ADMIN_TOKEN} 读取；空白表示未配置 →
 * 管理端一律 503 {@code ADMIN_NOT_CONFIGURED}。token 不打日志、不进响应体。
 */
@Data
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /** 管理端访问令牌（X-Admin-Token 头）；空白=未配置。 */
    private String token = "";
}
