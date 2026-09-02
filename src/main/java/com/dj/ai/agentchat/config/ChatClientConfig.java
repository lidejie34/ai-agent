package com.dj.ai.agentchat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 装配：基于 Spring AI 自动配置提供的 {@link ChatClient.Builder} 原型 bean
 * 构建单例 {@link ChatClient}。后续 MCP/Agent 迭代在此扩展 default advisors /
 * system prompt，控制器与服务层骨架不变。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
