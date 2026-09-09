package com.dj.ai.agentchat.tool.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期 best-effort 建表（插入迭代 G）：DB 在启动时可达则工具两表立即出现；
 * 不可达则仅 warn，不向上下文抛异常——不阻断启动、不影响 contextLoads，
 * 后续懒建表路径在 DB 恢复后自愈。
 *
 * <p>工具行不再自动种子：注册表内容完全由管理端维护。
 */
@Slf4j
public class ToolSchemaStartupRunner implements ApplicationRunner {

    private final ToolSchemaInitializer schemaInitializer;

    public ToolSchemaStartupRunner(ToolSchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            schemaInitializer.ensureSchema();
        } catch (Throwable t) {
            // 全异常吞掉：启动期建表只是开发友好的提前准备，非功能前提
            log.warn("启动期工具表初始化失败（不阻断启动，将在首次工具路径懒建表重试）: {}",
                    t.getMessage());
        }
    }
}
