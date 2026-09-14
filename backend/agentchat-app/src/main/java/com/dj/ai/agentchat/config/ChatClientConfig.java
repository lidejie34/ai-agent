package com.dj.ai.agentchat.config;

import com.dj.ai.agentchat.advisor.RequestLoggingAdvisor;
import com.dj.ai.agentchat.observability.ObservabilityMetricsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * ChatClient 装配（迭代 2 起为 Advisor 链的<b>唯一挂载点</b>）：
 * 基于 Spring AI 自动配置提供的 {@link ChatClient.Builder} 原型 bean 构建单例 {@link ChatClient}，
 * 默认增强（system prompt、Advisor、后续 ToolCallback）全部在此集中施加，
 * 控制器与服务层骨架不为此改动。
 *
 * <p>迭代9：{@link ObservabilityMetricsAdvisor} 经 {@link ObjectProvider} 条件挂载——
 * 可观测性总开关关闭时 bean 缺席，Advisor 链与迭代8 完全一致（AC-1）。
 *
 * <p>后续能力挂载位（本期只立点不实现）：
 * <ul>
 *   <li>迭代 A ChatMemory（会话记忆，MySQL 持久化）：
 *       {@code builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())}
 *       （M7 自带 org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor）；</li>
 *   <li>迭代 B MCP 工具：{@code builder.defaultTools(ToolCallback...)} /
 *       {@code builder.defaultTools(ToolCallbackProvider...)}；</li>
 *   <li>RAG：检索增强 Advisor 同样走 defaultAdvisors；请求级参数覆盖暂不提供（模型由全局配置决定）。</li>
 * </ul>
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 RequestLoggingAdvisor requestLoggingAdvisor,
                                 @Value("${app.chat.system-prompt:}") String systemPrompt,
                                 ObjectProvider<ObservabilityMetricsAdvisor> observabilityMetricsAdvisor) {
        // 日志 Advisor 无条件挂载（call/stream 双路径，模型边界摘要日志）
        chatClientBuilder.defaultAdvisors(requestLoggingAdvisor);

        // 迭代9：可观测性指标 Advisor 条件挂载（开关关闭 → provider 空 → 链与迭代8 一致）
        ObservabilityMetricsAdvisor metricsAdvisor = observabilityMetricsAdvisor == null
                ? null : observabilityMetricsAdvisor.getIfAvailable();
        if (metricsAdvisor != null) {
            chatClientBuilder.defaultAdvisors(metricsAdvisor);
        }

        // 默认 system prompt：配置非空白才施加；空白则不调用 defaultSystem，行为与迭代 1 完全一致
        if (StringUtils.hasText(systemPrompt)) {
            chatClientBuilder.defaultSystem(systemPrompt);
        }
        return chatClientBuilder.build();
    }

    /**
     * 迭代8 前既有 3 参便捷方法（非 @Bean）：委托 4 参主方法，Advisor provider 传 null
     * （不挂载指标 Advisor）——ChatClientAdvisorConfigTest 等既有直调零改动，且关闭态
     * 链形态逐字节回归。
     */
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 RequestLoggingAdvisor requestLoggingAdvisor,
                                 String systemPrompt) {
        return chatClient(chatClientBuilder, requestLoggingAdvisor, systemPrompt, null);
    }
}
