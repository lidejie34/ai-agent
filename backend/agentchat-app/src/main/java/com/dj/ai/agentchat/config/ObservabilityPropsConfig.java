package com.dj.ai.agentchat.config;

import com.dj.ai.agentchat.observability.ObservabilityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置绑定（迭代9）：无条件绑定 {@link ObservabilityProperties}——
 * properties bean 恒在场（enabled=false 即默认关），ChatService / PlannerClient /
 * ExecutorClient 构造注入不需 {@code @ConditionalOnProperty}，避免条件装配影响
 * 既有注入路径。逐字复刻 {@code ChatEvidenceConfig}（迭代8）模式。
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityPropsConfig {
}
