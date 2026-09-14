package com.dj.ai.agentchat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 工具证据配置绑定（迭代8）：无条件绑定 {@link ChatEvidenceProperties}——
 * properties bean 恒在场（enabled=false 即默认关），ChatService 构造注入不需
 * {@code @ConditionalOnProperty}，避免条件装配影响既有注入路径。
 */
@Configuration
@EnableConfigurationProperties(ChatEvidenceProperties.class)
public class ChatEvidenceConfig {
}
