package com.dj.ai.agentchat.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆参数（迭代3，前缀 {@code app.chat.memory}）：全部外置可配，缺省值即安全。
 *
 * <p>{@code enabled=false} 时记忆 Mapper/Store bean 不装配、不建表、会话路径请求 400
 * （FR-17）；{@code maxHistory} 仅控制续接加载窗口，落库全量不裁剪（FR-7）；
 * {@code initOnStartup} 控制启动期 best-effort 建表（失败仅 warn，不阻断启动）。
 */
@Data
@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryProperties {

    /** 记忆总开关（默认开启）；false 时不装配记忆相关 bean，会话路径返回 400。 */
    private boolean enabled = true;

    /** 续接时加载最近 N 条历史消息（默认 20）；落库全量不裁剪。 */
    private int maxHistory = 20;

    /** 启动期 best-effort 建表开关（默认开启）；DB 不可达时仅 warn，不阻断启动。 */
    private boolean initOnStartup = true;
}
