package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MyBatis-Plus + MySQL 的 {@link ConversationStore} 实现（迭代3）。
 *
 * <p>每个公有方法入口先 {@link ChatMemorySchemaInitializer#ensureSchema()} 懒建表；
 * {@code add} 标注 {@link Transactional}：seed/user/assistant 逐条 insert 在同一事务同一连接，
 * 任一失败整体回滚（FR-11 成对原子性；事务由 service 经代理调用生效，本类不内部自调用 add）。
 * Mapper 异常经 MyBatisExceptionTranslator 转译为 Spring {@code DataAccessException} 体系，
 * 由服务层统一映射 503/500。
 *
 * <p>bean 收口在 {@code ChatMemoryConfig}（@ConditionalOnProperty(enabled) 条件装配，
 * 故不使用 @Repository 无条件组件扫描）；@Transactional 经注入的 ConversationStore 代理生效。
 */
@Slf4j
public class MybatisChatMemory implements ConversationStore {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMemorySchemaInitializer schemaInitializer;

    public MybatisChatMemory(ChatSessionMapper chatSessionMapper,
                             ChatMessageMapper chatMessageMapper,
                             ChatMemorySchemaInitializer schemaInitializer) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        schemaInitializer.ensureSchema();
        for (Message message : messages) {
            ChatMessagePO po = new ChatMessagePO();
            po.setSessionId(conversationId);
            po.setRole(roleOf(message.getMessageType()));
            po.setContent(message.getText());
            chatMessageMapper.insert(po);
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        schemaInitializer.ensureSchema();
        List<ChatMessagePO> pos = chatMessageMapper.selectRecent(conversationId, lastN);
        List<Message> messages = new ArrayList<>(pos.size());
        for (ChatMessagePO po : pos) {
            messages.add(toMessage(po));
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        schemaInitializer.ensureSchema();
        chatMessageMapper.deleteBySessionId(conversationId);
    }

    @Override
    public void createSessionIfAbsent(String sessionId) {
        schemaInitializer.ensureSchema();
        chatSessionMapper.insertIgnoreSession(sessionId);
    }

    // ---- 内部 ----

    private static String roleOf(MessageType type) {
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            // TOOL 等类型本期无落库语义，防御性拒绝（不静默写错角色污染历史）
            default -> throw new IllegalArgumentException("不支持落库的消息 role: " + type);
        };
    }

    private static Message toMessage(ChatMessagePO po) {
        return switch (po.getRole()) {
            case "user" -> new UserMessage(po.getContent());
            case "assistant" -> new AssistantMessage(po.getContent());
            case "system" -> new SystemMessage(po.getContent());
            default -> throw new IllegalArgumentException("未知消息 role: " + po.getRole());
        };
    }
}
