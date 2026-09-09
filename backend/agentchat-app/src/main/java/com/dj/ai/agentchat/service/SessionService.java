package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.dto.session.SessionDeleteResult;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.util.SessionIds;
import com.dj.ai.agentchat.util.TextTitleUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理服务（迭代4）：会话列表/历史消息/删除/重命名的业务编排与异常归一。
 *
 * <p><b>常驻 {@code @Service}</b>（控制器始终可注入）；数据操作依赖 {@link SessionManager}，
 * 经 {@link ObjectProvider} 可选注入——Manager bean 随 {@code app.chat.memory.enabled}
 * 条件装配（与 ConversationStore 同生灭）。开关关闭或 bean 缺席时，每个公有方法首行
 * {@link #requireManager()} 抛 {@link InvalidChatRequestException}（400，与聊天会话路径语义一致，
 * FR-9.1/AC-12）；DB/建表/连接失败（{@link DataAccessException}）归一为
 * {@link MemoryUnavailableException}（503，FR-9.2/AC-13）；会话行不存在 →
 * {@link SessionNotFoundException}（404）。
 *
 * <p>列表组装恒为 2 次查询（0 会话时为 1 次）：Q1 会话倒序 + Q2 批量 IN JOIN 取各会话最近一条
 * 消息，内存组装预览，无 N+1（AC-14）。时间字段直接透传 {@code LocalDateTime}，由 fastjson2 序列化。
 */
@Slf4j
@Service
public class SessionService {

    /** 标题长度上限（code point 计，与 MySQL VARCHAR(200) 同口径）。 */
    private static final int TITLE_MAX_LENGTH = 200;
    private static final String SESSION_NOT_FOUND_MESSAGE = "会话不存在或已被删除";
    private static final String TITLE_BLANK_MESSAGE = "title 不能为空";
    private static final String TITLE_TOO_LONG_MESSAGE = "title 长度须为 1–200 字符";

    private final ObjectProvider<SessionManager> managerProvider;
    private final boolean memoryEnabled;

    public SessionService(ObjectProvider<SessionManager> managerProvider,
                          @Value("${app.chat.memory.enabled:true}") boolean memoryEnabled) {
        this.managerProvider = managerProvider;
        this.memoryEnabled = memoryEnabled;
    }

    /**
     * 会话列表（updated_at 倒序）+ 最近一条消息预览；空会话 preview 字段为 null。
     */
    public List<SessionSummary> listSessions() {
        SessionManager manager = requireManager();
        try {
            List<ChatSessionPO> sessions = manager.listSessions();
            if (sessions.isEmpty()) {
                return List.of();
            }
            List<String> ids = sessions.stream().map(ChatSessionPO::getSessionId).toList();
            Map<String, ChatMessagePO> latestBySession = mapBySession(manager.latestMessages(ids));
            List<SessionSummary> result = new ArrayList<>(sessions.size());
            for (ChatSessionPO session : sessions) {
                ChatMessagePO latest = latestBySession.get(session.getSessionId());
                result.add(new SessionSummary(
                        session.getSessionId(),
                        session.getTitle(),
                        session.getCreatedAt(),
                        session.getUpdatedAt(),
                        latest == null ? null : latest.getRole(),
                        latest == null ? null : TextTitleUtils.buildPreview(latest.getContent())));
            }
            return result;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * 某会话全量历史消息（id/时间升序、全文）。非法 UUID → 400；不存在 → 404。
     */
    public List<SessionMessageView> listMessages(String sessionId) {
        SessionManager manager = requireManager();
        SessionIds.requireUuid(sessionId);
        findExisting(manager, sessionId);
        try {
            return manager.listMessagesAscending(sessionId).stream()
                    .map(po -> new SessionMessageView(po.getRole(), po.getContent(), po.getCreatedAt()))
                    .toList();
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * 删除会话及其全部消息（同事务级联，无孤儿）。重复删除/不存在 → 404。
     */
    public SessionDeleteResult deleteSession(String sessionId) {
        SessionManager manager = requireManager();
        SessionIds.requireUuid(sessionId);
        findExisting(manager, sessionId);
        try {
            manager.deleteCascade(sessionId);
            return SessionDeleteResult.OK;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * 重命名：trim 后 1–200 字符（code point 计）；存储 trimmed 值，返回更新后会话（不含预览）。
     */
    public SessionSummary renameSession(String sessionId, String rawTitle) {
        SessionManager manager = requireManager();
        SessionIds.requireUuid(sessionId);
        String title = validateTitle(rawTitle);
        findExisting(manager, sessionId);
        try {
            ChatSessionPO updated = manager.rename(sessionId, title);
            return new SessionSummary(
                    updated.getSessionId(),
                    updated.getTitle(),
                    updated.getCreatedAt(),
                    updated.getUpdatedAt(),
                    null,
                    null);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    // ---- 内部 ----

    private SessionManager requireManager() {
        if (!memoryEnabled) {
            throw new InvalidChatRequestException(ChatService.MEMORY_DISABLED_MESSAGE);
        }
        SessionManager manager = managerProvider == null ? null : managerProvider.getIfAvailable();
        if (manager == null) {
            // 双保险：开关开但 bean 缺席（理论上不发生，配置收口一致）
            throw new InvalidChatRequestException(ChatService.MEMORY_DISABLED_MESSAGE);
        }
        return manager;
    }

    private ChatSessionPO findExisting(SessionManager manager, String sessionId) {
        ChatSessionPO po;
        try {
            po = manager.findSession(sessionId);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        if (po == null) {
            throw new SessionNotFoundException(SESSION_NOT_FOUND_MESSAGE);
        }
        return po;
    }

    private static String validateTitle(String rawTitle) {
        if (rawTitle == null) {
            throw new InvalidChatRequestException(TITLE_BLANK_MESSAGE);
        }
        String title = rawTitle.trim();
        if (title.isEmpty()) {
            throw new InvalidChatRequestException(TITLE_BLANK_MESSAGE);
        }
        long codePoints = title.codePoints().count();
        if (codePoints < 1 || codePoints > TITLE_MAX_LENGTH) {
            throw new InvalidChatRequestException(TITLE_TOO_LONG_MESSAGE);
        }
        return title;
    }

    private static Map<String, ChatMessagePO> mapBySession(Collection<ChatMessagePO> messages) {
        Map<String, ChatMessagePO> map = new HashMap<>(messages.size() * 2);
        for (ChatMessagePO message : messages) {
            // 每会话仅一条（MAX(id)），无重复键；防御性 putIfAbsent 兜底
            map.putIfAbsent(message.getSessionId(), message);
        }
        return map;
    }

    private static MemoryUnavailableException unavailable(DataAccessException e) {
        log.warn("会话服务数据访问失败（记忆服务不可用）: {}", e.getMessage());
        return new MemoryUnavailableException(ChatService.MEMORY_UNAVAILABLE_MESSAGE, e);
    }
}
