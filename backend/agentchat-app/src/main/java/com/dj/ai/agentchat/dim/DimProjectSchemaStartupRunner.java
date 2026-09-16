package com.dj.ai.agentchat.dim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期 best-effort 建维度项目表（迭代10 迁移，范式复刻 {@code ChatMemorySchemaStartupRunner}）：
 * DB 可达则启动即建表；不可达仅 warn 不阻断启动，后续懒建表路径在 DB 恢复后自愈。
 */
@Slf4j
public class DimProjectSchemaStartupRunner implements ApplicationRunner {

    private final DimProjectSchemaInitializer schemaInitializer;

    public DimProjectSchemaStartupRunner(DimProjectSchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            schemaInitializer.ensureSchema();
        } catch (Throwable t) {
            // 全异常吞掉：启动期建表只是开发友好的提前建表，非功能前提
            log.warn("启动期维度项目建表失败（不阻断启动，将在首次维度路径懒建表重试）: {}",
                    t.getMessage());
        }
    }
}
