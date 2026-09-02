package com.dj.ai.agentchat.config;

import com.dj.ai.agentchat.advisor.RequestLoggingAdvisor;
import com.dj.ai.agentchat.config.http.ChatHttpProperties;
import com.dj.ai.agentchat.sse.SseHeartbeatScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1（依赖与上下文）：迭代 2 新增的地基 bean 全部由容器装配，且离线上下文（无 Key/无 MySQL/Redis/PG）
 * 可正常刷新。任一 bean 缺失或自动配置冲突（如误定义 OpenAiApi bean 导致模型链未消费）此测试先红。
 */
@SpringBootTest
class ContextResilienceBeansTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void httpCustomizerBeans_arePresent() {
        assertThat(context.getBeansOfType(RestClientCustomizer.class)).isNotEmpty();
        assertThat(context.getBeansOfType(WebClientCustomizer.class)).isNotEmpty();
    }

    @Test
    void customRetryTemplate_isPresent_overridingAutoConfig() {
        assertThat(context.getBeansOfType(RetryTemplate.class)).isNotEmpty();
    }

    @Test
    void requestLoggingAdvisor_isPresent() {
        assertThat(context.getBeansOfType(RequestLoggingAdvisor.class)).isNotEmpty();
    }

    @Test
    void heartbeatScheduler_isPresent() {
        assertThat(context.getBeansOfType(SseHeartbeatScheduler.class)).isNotEmpty();
    }

    @Test
    void httpProperties_bindWithDefaults() {
        ChatHttpProperties props = context.getBean(ChatHttpProperties.class);
        assertThat(props.getSync().getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getSync().getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getSync().getConnectionRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getSync().getPool().getMaxTotal()).isEqualTo(20);
        assertThat(props.getSync().getPool().getMaxPerRoute()).isEqualTo(20);
        assertThat(props.getStream().getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getStream().getResponseTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getStream().getPool().getMaxConnections()).isEqualTo(20);
        assertThat(props.getStream().getPool().getPendingAcquireMaxCount()).isEqualTo(40);
        assertThat(props.getStream().getPool().getPendingAcquireTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getStream().getPool().getMaxIdleTime()).isEqualTo(Duration.ofSeconds(30));
    }
}
