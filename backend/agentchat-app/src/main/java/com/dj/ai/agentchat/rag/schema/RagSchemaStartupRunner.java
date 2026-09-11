package com.dj.ai.agentchat.rag.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期 best-effort 建表（迭代6）：PG 在启动时可达则 vector 扩展与 RAG 两表立即就绪；
 * 不可达仅 warn，不向上下文抛异常——不阻断启动，后续懒建表路径在 PG 恢复后自愈。
 */
@Slf4j
public class RagSchemaStartupRunner implements ApplicationRunner {

    private final RagSchemaInitializer schemaInitializer;

    public RagSchemaStartupRunner(RagSchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            schemaInitializer.ensureSchema();
        } catch (Throwable t) {
            // 全异常吞掉：启动期建表只是开发友好的提前准备，非功能前提
            log.warn("启动期 RAG 表初始化失败（不阻断启动，将在首次知识库路径懒建表重试）: {}",
                    t.getMessage());
        }
    }
}
