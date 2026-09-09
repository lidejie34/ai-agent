package com.dj.ai.agentchat.memory.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 基于 MyBatis-Plus + MySQL 的 {@link SessionManager} 实现（迭代4）。
 *
 * <p>每个公有方法入口先 {@link ChatMemorySchemaInitializer#ensureSchema()} 懒建表（兼作 DB
 * 探测：不可达时抛 {@code DataAccessException}，由 service 归一为 503）；级联删除标注
 * {@link Transactional}：先删消息后删会话，同一事务同一连接，不留孤儿消息（FR-7）。
 *
 * <p>列表查询恒为 2 次（0 会话时 service 跳过第 2 次）：Q1 会话倒序列表 +
 * Q2 批量 IN JOIN 取各会话最近一条消息，不随会话数线性增长（AC-14 无 N+1）。
 *
 * <p>bean 收口在 {@code ChatMemoryConfig}（随 {@code app.chat.memory.enabled} 条件装配，
 * 故不使用 @Repository 无条件组件扫描）；@Transactional 经注入的 SessionManager 代理生效。
 */
@Slf4j
public class MybatisSessionManager implements SessionManager {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMemorySchemaInitializer schemaInitializer;

    public MybatisSessionManager(ChatSessionMapper chatSessionMapper,
                                 ChatMessageMapper chatMessageMapper,
                                 ChatMemorySchemaInitializer schemaInitializer) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public List<ChatSessionPO> listSessions() {
        schemaInitializer.ensureSchema();
        return chatSessionMapper.selectList(
                new QueryWrapper<ChatSessionPO>().orderByDesc("updated_at"));
    }

    @Override
    public List<ChatMessagePO> latestMessages(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            // 0 会话不发 IN 查询（避免 IN () 语法错误）
            return List.of();
        }
        return chatMessageMapper.selectLatestPerSession(sessionIds);
    }

    @Override
    public List<ChatMessagePO> listMessagesAscending(String sessionId) {
        schemaInitializer.ensureSchema();
        return chatMessageMapper.selectAllAscending(sessionId);
    }

    @Override
    public ChatSessionPO findSession(String sessionId) {
        schemaInitializer.ensureSchema();
        return chatSessionMapper.selectById(sessionId);
    }

    @Override
    @Transactional
    public void deleteCascade(String sessionId) {
        schemaInitializer.ensureSchema();
        // 先删消息后删会话：同事务，任一失败整体回滚，无孤儿消息
        chatMessageMapper.deleteBySessionId(sessionId);
        chatSessionMapper.deleteById(sessionId);
    }

    @Override
    public ChatSessionPO rename(String sessionId, String title) {
        schemaInitializer.ensureSchema();
        // 局部 PO：仅主键 + title 非空 → MP NOT_NULL 策略只 SET title；
        // createdAt/updatedAt 留空不进 SET，updated_at 由 MySQL ON UPDATE CURRENT_TIMESTAMP 维护
        ChatSessionPO patch = new ChatSessionPO();
        patch.setSessionId(sessionId);
        patch.setTitle(title);
        chatSessionMapper.updateById(patch);
        return chatSessionMapper.selectById(sessionId);
    }
}
