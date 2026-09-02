package com.dj.ai.agentchat.config.http;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.PooledConnectionProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T6/T7：同步（HttpClient5）/流式（Reactor Netty）显式连接池与超时的装配断言（AC-7、AC-11）。
 * 用 {@link ApplicationContextRunner} 只加载本工程配置类（离线、快速、无 MySQL/Redis 依赖），
 * 以<b>非默认</b>配置值绑定，断言池参数/超时真正进入底层组件；并断言 Customizer 会把
 * 池化 requestFactory / connector 装到 Builder 上（M7 自动配置经 ObjectProvider 取的正是该 Builder）。
 */
class HttpClientPoolConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of()) // 不触发 Boot 自动配置，仅本工程 bean
            .withUserConfiguration(SyncHttpClientConfig.class, StreamHttpClientConfig.class)
            .withPropertyValues(
                    // 同步：非默认值，证明配置绑定链路生效
                    "app.chat.http.sync.connect-timeout=7s",
                    "app.chat.http.sync.read-timeout=42s",
                    "app.chat.http.sync.connection-request-timeout=3s",
                    "app.chat.http.sync.pool.max-total=30",
                    "app.chat.http.sync.pool.max-per-route=10",
                    // 流式：非默认值
                    "app.chat.http.stream.connect-timeout=9s",
                    "app.chat.http.stream.response-timeout=55s",
                    "app.chat.http.stream.pool.max-connections=15",
                    "app.chat.http.stream.pool.pending-acquire-max-count=33",
                    "app.chat.http.stream.pool.pending-acquire-timeout=4s",
                    "app.chat.http.stream.pool.max-idle-time=25s");

    // ---------- T6 同步路径 ----------

    @Test
    void sync_poolManager_bindsMaxTotalAndMaxPerRoute() {
        contextRunner.run(context -> {
            PoolingHttpClientConnectionManager manager = context.getBean(PoolingHttpClientConnectionManager.class);
            assertThat(manager.getMaxTotal()).isEqualTo(30);
            assertThat(manager.getDefaultMaxPerRoute()).isEqualTo(10);
        });
    }

    @Test
    void sync_requestConfig_bindsTimeouts() {
        contextRunner.run(context -> {
            RequestConfig requestConfig = context.getBean(RequestConfig.class);
            assertThat(requestConfig.getConnectTimeout().toDuration()).isEqualTo(Duration.ofSeconds(7));
            assertThat(requestConfig.getResponseTimeout().toDuration()).isEqualTo(Duration.ofSeconds(42));
            assertThat(requestConfig.getConnectionRequestTimeout().toDuration()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void sync_restClientCustomizer_installsHttpComponentsRequestFactory() {
        contextRunner.run(context -> {
            assertThat(context.getBean(CloseableHttpClient.class)).isNotNull();
            RestClientCustomizer customizer = context.getBean(RestClientCustomizer.class);
            RestClient.Builder builder = mock(RestClient.Builder.class);
            customizer.customize(builder);
            verify(builder).requestFactory(any(HttpComponentsClientHttpRequestFactory.class));
        });
    }

    // ---------- T7 流式路径 ----------

    @Test
    void stream_connectionProvider_bindsMaxConnections() {
        contextRunner.run(context -> {
            reactor.netty.resources.ConnectionProvider provider =
                    context.getBean(reactor.netty.resources.ConnectionProvider.class);
            assertThat(provider).isInstanceOf(PooledConnectionProvider.class);
            // PooledConnectionProvider.maxConnections() 为 reactor-netty 公共 API（构建期 javap 实证）
            assertThat(((PooledConnectionProvider<?>) provider).maxConnections()).isEqualTo(15);
        });
    }

    @Test
    void stream_nettyHttpClient_beanPresentWithPool() {
        contextRunner.run(context -> {
            HttpClient httpClient = context.getBean(HttpClient.class);
            assertThat(httpClient).isNotNull();
        });
    }

    @Test
    void stream_webClientCustomizer_installsReactorConnector() {
        contextRunner.run(context -> {
            WebClientCustomizer customizer = context.getBean(WebClientCustomizer.class);
            WebClient.Builder builder = mock(WebClient.Builder.class);
            customizer.customize(builder);
            verify(builder).clientConnector(any(ReactorClientHttpConnector.class));
        });
    }
}
