package com.dj.ai.agentchat.config.http;

import io.netty.channel.ChannelOption;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 流式模型调用路径（OpenAiApi 内部的 WebClient）：显式 Reactor Netty 连接池。
 *
 * <p>M7 jar 实证：自动配置经 {@code ObjectProvider<WebClient.Builder>} 取 Builder，Boot 的
 * {@code WebClientAutoConfiguration} 会应用容器中全部 {@link WebClientCustomizer}；
 * {@code ReactorClientHttpConnector} 在 spring-web jar 中（org.springframework.http.client.reactive）。
 * 引入 reactor-netty-http 后 Boot 默认也会提供 connector，但 Customizer 显式 clientConnector 覆盖之，
 * 本命名连接池生效。
 */
@Configuration
public class StreamHttpClientConfig {

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider arkConnectionProvider(ChatHttpProperties properties) {
        ChatHttpProperties.StreamPool pool = properties.getStream().getPool();
        return ConnectionProvider.builder("ark-stream-pool")
                .maxConnections(pool.getMaxConnections())
                .pendingAcquireMaxCount(pool.getPendingAcquireMaxCount())
                .pendingAcquireTimeout(pool.getPendingAcquireTimeout())
                .maxIdleTime(pool.getMaxIdleTime())
                .build();
    }

    @Bean
    public HttpClient arkNettyHttpClient(ConnectionProvider arkConnectionProvider,
                                         ChatHttpProperties properties) {
        ChatHttpProperties.Stream stream = properties.getStream();
        return HttpClient.create(arkConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) stream.getConnectTimeout().toMillis())
                .responseTimeout(stream.getResponseTimeout())
                .compress(true);
    }

    @Bean
    public WebClientCustomizer arkWebClientCustomizer(HttpClient arkNettyHttpClient) {
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(arkNettyHttpClient));
    }
}
