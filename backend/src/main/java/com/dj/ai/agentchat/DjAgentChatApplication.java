package com.dj.ai.agentchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * dj-agent-chat 启动类：Spring Boot 3.4 + Spring AI 1.0.0-M7（OpenAI 兼容方式接入火山方舟）。
 *
 * <p>不添加任何 {@code @EnableXxx} 存储相关注解；本期不引 JPA/actuator，
 * MySQL/Redis/Postgres 仅为「依赖 + 连接配置」预留，缺库缺 Key 均可启动。
 */
@SpringBootApplication
public class DjAgentChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(DjAgentChatApplication.class, args);
    }
}
