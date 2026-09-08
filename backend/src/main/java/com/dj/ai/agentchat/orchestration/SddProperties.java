package com.dj.ai.agentchat.orchestration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SDD 子 Agent 编排配置（迭代5，前缀 {@code app.sdd}）。
 *
 * <p>语义备注（AC-56/AC-57/AC-59）：
 * <ul>
 *   <li>{@code enabled} 默认 false（matchIfMissing=false）：关闭时编排全家桶不装配，
 *       其余键不产生行为、不导致启动错误；</li>
 *   <li>角色 {@code model} 空白 = 继承 {@code spring.ai.openai.chat.options.model}；</li>
 *   <li>角色 {@code systemPrompt} 空白 = 回退内置默认提示词常量
 *       （编排角色必有提示词，与 {@code app.chat.system-prompt} 空白=不注入语义不同）；</li>
 *   <li>{@code taskTimeout} null = 继承模型 HTTP 读超时（60s），受总预算剩余时间兜底。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "app.sdd")
public class SddProperties {

    /** 编排总开关：false（默认）时编排 bean 不装配、零新帧零额外模型调用，行为=迭代4。 */
    private boolean enabled = false;

    /** Planner 调用轮次硬顶（路由=第 1 轮；含路由与再规划）。 */
    private int maxRounds = 6;

    /** 累计子任务数硬顶（含执行/重试/跳过）。 */
    private int maxTasks = 8;

    /** 连续失败硬顶（成功/跳过重置计数）。 */
    private int maxConsecutiveFailures = 2;

    /** SSE 路径总墙钟预算（必须短于容器 sse-timeout 120s）。 */
    private Duration totalBudgetSse = Duration.ofSeconds(110);

    /** 同步路径总墙钟预算（必须短于同步 read-timeout 60s）。 */
    private Duration totalBudgetSync = Duration.ofSeconds(55);

    /** 单子任务超时；null = 继承模型 HTTP 超时（由总预算剩余时间兜底）。 */
    private Duration taskTimeout = null;

    /** Planner 角色配置（路由/再规划/汇总共用）。 */
    private Role planner = new Role();

    /** Executor 角色配置。 */
    private ExecutorRole executor = new ExecutorRole();

    @Data
    public static class Role {
        /** 角色模型 ID；空白 = 继承主模型。 */
        private String model = "";
        /** 角色系统提示词；空白 = 回退 SddPrompts 内置常量。 */
        private String systemPrompt = "";
        /** 采样温度；null = 继承默认。 */
        private Double temperature = null;
    }

    @Data
    public static class ExecutorRole extends Role {
        /** 子任务结果回灌 Planner 前的截断字符数（默认 2000，附截断标记）。 */
        private int maxResultChars = 2000;
    }
}
