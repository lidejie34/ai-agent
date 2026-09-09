package com.dj.ai.agentchat.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代4 冒烟实证：MySQL 容器默认 UTC，connector-j 9.x 的 {@code serverTimezone} 仅弃用别名、
 * 不强制 MySQL 会话时区；MyBatis 把 TIMESTAMP 读成 LocalDateTime 取会话墙钟，缺这两个参数时
 * API 返回时间比北京时间晚 8 小时。此测试锁定 JDBC URL 必须带正式参数。
 */
@SpringBootTest
class DatasourceTimeZoneConfigTest {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Test
    void jdbcUrl_forcesMysqlSessionTimeZoneToShanghai() {
        assertThat(datasourceUrlSafe())
                .contains("connectionTimeZone=Asia/Shanghai")
                .contains("forceConnectionTimeZoneToSession=true");
    }

    private String datasourceUrlSafe() {
        return datasourceUrl == null ? "" : datasourceUrl;
    }
}
