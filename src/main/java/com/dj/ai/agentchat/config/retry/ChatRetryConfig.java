package com.dj.ai.agentchat.config.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * 模型同步调用重试策略（迭代 2）。
 *
 * <p>M7 jar 实证（字节码反汇编）：
 * <ul>
 *   <li>Spring AI 的 RetryTemplate 仅作用于同步调用（OpenAiChatModel.internalCall 回调内
 *       retryTemplate.execute）；流式 internalStream/stream 不引用 RetryTemplate（流式首片段前
 *       重试在 ChatService 以 Flux.retryWhen 实现）；</li>
 *   <li>自动配置的 retryTemplate bean 为 {@code @Bean @ConditionalOnMissingBean}，可被本 bean 覆盖；</li>
 *   <li>RetryTemplateBuilder 默认 traverseCauses=false 且仅 retryOn(TransientAiException)，
 *       故网络故障 ResourceAccessException（连接拒绝/建连超时/读超时）默认<b>不</b>重试——
 *       此处显式追加 ResourceAccessException 并开启 traversingCauses；</li>
 *   <li>429 经 spring.ai.retry.on-http-codes:[429] 由 ResponseErrorHandler 翻译为 TransientAiException
 *       （状态码分类第一分支优先）；其余 4xx 为 NonTransientAiException，不在重试集合，立即失败。</li>
 * </ul>
 * 重试次数与退避参数读取 spring.ai.retry.* 绑定值（单一配置源）；可重试异常集合为代码固定策略。
 */
@Slf4j
@Configuration
public class ChatRetryConfig {

    @Bean
    public RetryTemplate retryTemplate(SpringAiRetryProperties retryProperties) {
        SpringAiRetryProperties.Backoff backoff = retryProperties.getBackoff();
        int maxAttempts = retryProperties.getMaxAttempts();
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .traversingCauses()
                .exponentialBackoff(backoff.getInitialInterval(),
                        backoff.getMultiplier(),
                        backoff.getMaxInterval())
                .withListener(new RetryListener() {
                    @Override
                    public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                        return true;
                    }

                    @Override
                    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                                                                 Throwable throwable) {
                        // 编码期实证（spring-retry 2.0.10 字节码）：onError 在 registerThrowable 之后调用，
                        // 首次失败 getRetryCount() 即为 1（= 已失败的尝试序号）。
                        // 安全：只打异常类名与 message（HTTP 状态码/响应体摘要），不含 Key（Key 在 header）。
                        log.warn("模型调用第 {} 次尝试失败: {}: {}",
                                context.getRetryCount(),
                                throwable.getClass().getSimpleName(),
                                throwable.getMessage());
                    }

                    @Override
                    public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback,
                                                               Throwable throwable) {
                        // close 在每次失败终止时都会回调（含不可重试快速失败）；仅重试耗尽时打耗尽日志。
                        if (throwable != null && context.getRetryCount() >= maxAttempts) {
                            log.warn("模型调用重试已耗尽（共 {} 次尝试）: {}",
                                    context.getRetryCount(), throwable.getClass().getSimpleName());
                        }
                    }
                })
                .build();
    }
}
