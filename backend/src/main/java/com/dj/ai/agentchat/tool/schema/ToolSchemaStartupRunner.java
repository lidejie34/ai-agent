package com.dj.ai.agentchat.tool.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期 best-effort 建表 + 种子（插入迭代 G）：DB 在启动时可达则两表立即出现并补齐
 * 种子工具行（AC-5/8）；不可达则仅 warn，不向上下文抛异常——不阻断启动、不影响
 * contextLoads，后续懒建表/懒种子路径在 DB 恢复后自愈。
 */
@Slf4j
public class ToolSchemaStartupRunner implements ApplicationRunner {

    private final ToolSchemaInitializer schemaInitializer;
    private final ToolSeeder toolSeeder;

    public ToolSchemaStartupRunner(ToolSchemaInitializer schemaInitializer, ToolSeeder toolSeeder) {
        this.schemaInitializer = schemaInitializer;
        this.toolSeeder = toolSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            schemaInitializer.ensureSchema();
            toolSeeder.seedIfAbsent();
        } catch (Throwable t) {
            // 全异常吞掉：启动期建表/种子只是开发友好的提前准备，非功能前提
            log.warn("启动期工具表初始化/种子失败（不阻断启动，将在首次工具路径懒建表重试）: {}",
                    t.getMessage());
        }
    }
}
