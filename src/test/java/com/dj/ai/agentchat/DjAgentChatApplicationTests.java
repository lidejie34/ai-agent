package com.dj.ai.agentchat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T1：上下文加载测试（AC-2 的自动化锁）。
 *
 * <p>在无 ARK_API_KEY、无 MySQL/Redis/Postgres 可达的环境下加载完整 Spring 上下文：
 * <ul>
 *   <li>Spring AI OpenAI 自动配置（api-key 为空也能构建 bean，构建期不发起 HTTP）；</li>
 *   <li>Hikari DataSource（initialization-fail-timeout=-1，启动不探测连接）；</li>
 *   <li>Lettuce ConnectionFactory（懒连接，首条命令才建连）。</li>
 * </ul>
 * 若未来误引 JPA/actuator 或改掉懒连接开关，此测试先红。
 */
@SpringBootTest
class DjAgentChatApplicationTests {

    @Test
    void contextLoads() {
        // 上下文能成功刷新即通过
    }
}
