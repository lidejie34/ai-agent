package com.dj.ai.agentchat.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.beans.factory.SmartInitializingSingleton;
import reactor.core.publisher.Hooks;

import java.util.Map;

/**
 * MDC 跨线程传递注册器（迭代9 FR-1.5 / NFR-5）：条件装配（总开关），
 * 一处注册、三类边界全覆盖。
 *
 * <ol>
 *   <li>{@link ContextRegistry} 注册 MDC {@link ThreadLocalAccessor}
 *       （get=MDC.getCopyOfContextMap / set=MDC.setContextMap / reset=MDC.clear）；</li>
 *   <li>{@code Hooks.enableAutomaticContextPropagation()}——Reactor 3.7 在 classpath 有
 *       context-propagation 时，订阅点快照全部已注册 ThreadLocal 进 Reactor Context，
 *       各操作符信号执行前恢复、后清理。覆盖：publishOn(boundedElastic) 落库段、
 *       Netty eventloop 上的流式 Advisor doOnNext/doOnComplete、控制器 SSE subscribe 消费者；</li>
 *   <li>三个自建 daemon 池（sdd-orchestrator / sdd-model-call / tool-executor，Reactor 管不到）
 *       在各自 @Bean 处条件包裹 {@code ContextExecutorService.wrap(...)}
 *       （见 SddRuntimeConfig / ToolRuntimeConfig）。</li>
 * </ol>
 *
 * <p>线程清理保证：filter finally remove（请求线程）；accessor reset（池线程/Reactor 线程）；
 * 无串号、无泄漏。关闭态本 bean 不装配，Hooks 不开启，行为与迭代8 逐字节一致。
 */
public class ObservabilityContextInitializer implements SmartInitializingSingleton {

    @Override
    public void afterSingletonsInstantiated() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    /**
     * MDC ThreadLocalAccessor：快照/恢复/复位整个 MDC 上下文 Map。
     * 复位用 {@code MDC.clear()}（清空本线程全部键），池线程不残留请求间串号。
     */
    static final class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

        static final String KEY = "mdc";

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public Map<String, String> getValue() {
            return MDC.getCopyOfContextMap();
        }

        @Override
        public void setValue(Map<String, String> value) {
            MDC.setContextMap(value);
        }

        @Override
        public void setValue() {
            MDC.clear();
        }

        @Override
        public void reset() {
            MDC.clear();
        }
    }
}
