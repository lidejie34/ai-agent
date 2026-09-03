package com.dj.ai.agentchat.memory.mybatis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期 best-effort 建表（迭代3，实证 5）：DB 在启动时可达则两表立即出现（AC-19）；
 * 不可达则仅 warn，不向上下文抛异常——不阻断启动、不影响 contextLoads（AC-16），
 * 后续懒建表路径在 DB 恢复后自愈。
 */
@Slf4j
public class ChatMemorySchemaStartupRunner implements ApplicationRunner {

    private final ChatMemorySchemaInitializer schemaInitializer;

    public ChatMemorySchemaStartupRunner(ChatMemorySchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            schemaInitializer.ensureSchema();
        } catch (Throwable t) {
            // 全异常吞掉：启动期建表只是开发友好的提前建表，非功能前提
            log.warn("启动期会话记忆建表失败（不阻断启动，将在首次记忆路径懒建表重试）: {}",
                    t.getMessage());
        }
    }
}
