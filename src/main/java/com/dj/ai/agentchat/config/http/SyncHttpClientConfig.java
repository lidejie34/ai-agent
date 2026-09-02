package com.dj.ai.agentchat.config.http;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * 同步模型调用路径（OpenAiApi 内部的 RestClient）：显式 Apache HttpClient5 连接池。
 *
 * <p>M7 jar 实证：{@code OpenAiChatAutoConfiguration} 经 {@code ObjectProvider<RestClient.Builder>}
 * 取 Boot 自动配置的 Builder，而该 Builder 会应用容器中全部 {@link RestClientCustomizer}；
 * OpenAiApi 构造器只追加 baseUrl/headers/statusHandler，Builder 上预设的 requestFactory 不会被覆盖。
 * 故不在此自定义 OpenAiApi bean（自动配置不消费它），只定制 Builder。
 */
@Configuration
@EnableConfigurationProperties(ChatHttpProperties.class)
public class SyncHttpClientConfig {

    @Bean
    public PoolingHttpClientConnectionManager arkPoolingHttpClientConnectionManager(ChatHttpProperties properties) {
        ChatHttpProperties.Pool pool = properties.getSync().getPool();
        // 编码期 javap 实证：HC5 5.4.1 builder 方法名为 setMaxConnTotal / setMaxConnPerRoute
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(pool.getMaxTotal())
                .setMaxConnPerRoute(pool.getMaxPerRoute())
                .build();
    }

    @Bean
    public RequestConfig arkRequestConfig(ChatHttpProperties properties) {
        ChatHttpProperties.Sync sync = properties.getSync();
        return RequestConfig.custom()
                .setConnectTimeout(Timeout.of(sync.getConnectTimeout()))
                .setResponseTimeout(Timeout.of(sync.getReadTimeout()))
                .setConnectionRequestTimeout(Timeout.of(sync.getConnectionRequestTimeout()))
                .build();
    }

    @Bean
    public CloseableHttpClient arkCloseableHttpClient(PoolingHttpClientConnectionManager connectionManager,
                                                       RequestConfig arkRequestConfig) {
        // 容器关闭时 CloseableHttpClient.close() 由 Spring 销毁推断自动调用，释放连接池
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(arkRequestConfig)
                .evictExpiredConnections()
                .build();
    }

    @Bean
    public RestClientCustomizer arkRestClientCustomizer(CloseableHttpClient arkCloseableHttpClient) {
        return builder -> builder.requestFactory(
                new HttpComponentsClientHttpRequestFactory(arkCloseableHttpClient));
    }
}
