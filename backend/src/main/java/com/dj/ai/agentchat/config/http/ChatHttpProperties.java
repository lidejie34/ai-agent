package com.dj.ai.agentchat.config.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 模型调用 HTTP 客户端参数（迭代 2）：同步路径（RestClient + Apache HttpClient5 连接池）
 * 与流式路径（WebClient + Reactor Netty 连接池）的池大小与超时，全部外置可配。
 *
 * <p>配置前缀 {@code app.chat.http}，缺省值即安全（连接 5s、读/响应 60s、池 20 连接），
 * 不引发重试风暴、不无限等待。
 */
@Data
@ConfigurationProperties(prefix = "app.chat.http")
public class ChatHttpProperties {

    private final Sync sync = new Sync();
    private final Stream stream = new Stream();

    @Data
    public static class Sync {
        /** HC5 连接建立超时（RequestConfig.connectTimeout）。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** HC5 响应超时（RequestConfig.responseTimeout，两次数据读取最大间隔）。 */
        private Duration readTimeout = Duration.ofSeconds(60);
        /** 从连接池获取连接的等待超时（RequestConfig.connectionRequestTimeout）。 */
        private Duration connectionRequestTimeout = Duration.ofSeconds(5);
        private final Pool pool = new Pool();
    }

    @Data
    public static class Pool {
        /** PoolingHttpClientConnectionManager.maxTotal。 */
        private int maxTotal = 20;
        /** defaultMaxPerRoute（方舟单端点，与 maxTotal 同值即可）。 */
        private int maxPerRoute = 20;
    }

    @Data
    public static class Stream {
        /** Netty ChannelOption.CONNECT_TIMEOUT_MILLIS。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** Netty HttpClient.responseTimeout（SSE 片段间隔上限；应小于 SSE 总超时 120s）。 */
        private Duration responseTimeout = Duration.ofSeconds(60);
        private final StreamPool pool = new StreamPool();
    }

    @Data
    public static class StreamPool {
        /** ConnectionProvider.maxConnections。 */
        private int maxConnections = 20;
        /** pendingAcquireMaxCount（排队获取连接上限）。 */
        private int pendingAcquireMaxCount = 40;
        /** pendingAcquireTimeout（排队获取连接超时）。 */
        private Duration pendingAcquireTimeout = Duration.ofSeconds(5);
        /** maxIdleTime（空闲连接回收）。 */
        private Duration maxIdleTime = Duration.ofSeconds(30);
    }
}
