package com.dj.ai.agentchat.config;

import com.dj.ai.agentchat.memory.ChatMemoryProperties;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.mybatis.ChatMemorySchemaInitializer;
import com.dj.ai.agentchat.memory.mybatis.ChatMemorySchemaStartupRunner;
import com.dj.ai.agentchat.memory.mybatis.MybatisChatMemory;
import com.dj.ai.agentchat.memory.mybatis.MybatisSessionManager;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 会话记忆装配（迭代3）：整体受 {@code app.chat.memory.enabled}（默认 true）开关收口。
 *
 * <p>开关关闭时：本配置不生效——{@link MapperScan} 不处理（记忆 Mapper 接口不标
 * {@code @Mapper}，MyBatis 自动扫描也不会兜底注册）、{@link ConversationStore} /
 * {@link SessionManager} /
 * {@link ChatMemorySchemaInitializer} / 启动 runner 等 bean 均不装配；
 * MybatisPlus 自动配置的 SqlSessionFactory/SqlSessionTemplate 仍存在但无人调用、构建期不连库。
 * ChatService 经 {@code ObjectProvider<ConversationStore>} 拿到 null，会话路径请求按
 * 「记忆功能未启用」返回 400（FR-17/AC-18），无状态路径不受影响。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.chat.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChatMemoryProperties.class)
@MapperScan(basePackages = "com.dj.ai.agentchat.memory.mapper")
public class ChatMemoryConfig {

    /**
     * 懒建表器：持 DataSource，首次记忆路径经 ScriptUtils 执行 classpath schema.sql。
     */
    @Bean
    public ChatMemorySchemaInitializer chatMemorySchemaInitializer(DataSource dataSource) {
        return new ChatMemorySchemaInitializer(dataSource);
    }

    /**
     * 会话存储：面向 {@link ConversationStore}（Spring AI ChatMemory 子接口）编程。
     */
    @Bean
    public ConversationStore conversationStore(ChatSessionMapper chatSessionMapper,
                                               ChatMessageMapper chatMessageMapper,
                                               ChatMemorySchemaInitializer chatMemorySchemaInitializer) {
        return new MybatisChatMemory(chatSessionMapper, chatMessageMapper, chatMemorySchemaInitializer);
    }

    /**
     * 会话管理（迭代4）：会话列表/历史消息/级联删除/重命名的数据操作，面向
     * {@link SessionManager} 编程；与 conversationStore 同生灭（开关关闭时不装配，
     * SessionService 经 ObjectProvider 拿到 null → 会话接口 400，FR-9.1/AC-12）。
     */
    @Bean
    public SessionManager sessionManager(ChatSessionMapper chatSessionMapper,
                                         ChatMessageMapper chatMessageMapper,
                                         ChatMemorySchemaInitializer chatMemorySchemaInitializer) {
        return new MybatisSessionManager(chatSessionMapper, chatMessageMapper, chatMemorySchemaInitializer);
    }

    /**
     * 启动期 best-effort 建表：失败仅 warn 不阻断启动；可用 app.chat.memory.init-on-startup=false 关闭。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.chat.memory", name = "init-on-startup",
            havingValue = "true", matchIfMissing = true)
    public ChatMemorySchemaStartupRunner chatMemorySchemaStartupRunner(
            ChatMemorySchemaInitializer chatMemorySchemaInitializer) {
        return new ChatMemorySchemaStartupRunner(chatMemorySchemaInitializer);
    }
}
