package com.dj.ai.agentchat.service;

import com.alibaba.fastjson2.JSON;
import com.dj.ai.agentchat.dto.session.SessionDeleteResult;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionScopeUpdate;
import com.dj.ai.agentchat.dto.session.SessionScopeView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.InvalidKbFilterException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.memory.SessionManager;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;
import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.support.KbMetaValidator;
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
import java.util.Set;

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
    /** 用户可见 role 白名单（迭代8）：tool_evidence 仅供模型回放，任何用户出口不可见。 */
    private static final Set<String> VISIBLE_ROLES = Set.of("user", "assistant", "system");

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
            // 迭代8：白名单过滤——证据行（tool_evidence）不下发前端；无证据会话结果逐字段不变
            return manager.listMessagesAscending(sessionId).stream()
                    .filter(po -> VISIBLE_ROLES.contains(po.getRole()))
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

    /**
     * 读会话级范围配置（迭代12 FR-3/D3）：配置行缺席 → 四字段全 null 视图（全默认）。
     * 仅服务 UI 恢复，不回灌模型链路。
     */
    public SessionScopeView getScope(String sessionId) {
        SessionManager manager = requireManager();
        SessionIds.requireUuid(sessionId);
        findExisting(manager, sessionId);
        try {
            ChatSessionScopePO po = manager.findScope(sessionId);
            if (po == null) {
                return SessionScopeView.ALL_DEFAULT;
            }
            return new SessionScopeView(
                    parseJsonArray(po.getKbProjects()),
                    parseJsonArray(po.getKbTags()),
                    parseJsonArray(po.getToolNames()),
                    parseJsonArray(po.getMcpServers()));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * 全量覆盖会话级范围配置（迭代12 D2）：kb 两维经 KbMetaValidator 白名单校验
     * （非法 400 KB_INVALID_FILTER，与对话请求同口径）；toolNames/mcpServers 仅
     * trim/去空/去重（未知名容忍——工具可能事后下线，恢复时自然过滤）。
     */
    public SessionScopeView updateScope(String sessionId, SessionScopeUpdate update) {
        SessionManager manager = requireManager();
        SessionIds.requireUuid(sessionId);
        findExisting(manager, sessionId);
        RagProperties.Meta meta = new RagProperties.Meta();
        List<String> kbProjects;
        List<String> kbTags;
        try {
            // 三态保持：入参 null 直接得 null（不经过规整器的 null→空集合归一）
            kbProjects = update == null || update.kbProjects() == null ? null
                    : KbMetaValidator.normalizeProjects(update.kbProjects(), meta.getMaxProjectLength());
            kbTags = update == null || update.kbTags() == null ? null
                    : KbMetaValidator.normalizeTags(update.kbTags(), meta.getMaxTags(), meta.getMaxTagLength());
        } catch (KbMetaValidator.KbMetaInvalidException e) {
            throw new InvalidKbFilterException("知识库过滤参数非法：" + e.getMessage());
        }
        List<String> toolNames = normalizeLoose(update == null ? null : update.toolNames());
        List<String> mcpServers = normalizeLoose(update == null ? null : update.mcpServers());
        ChatSessionScopePO po = new ChatSessionScopePO();
        po.setSessionId(sessionId);
        po.setKbProjects(toJson(kbProjects));
        po.setKbTags(toJson(kbTags));
        po.setToolNames(toJson(toolNames));
        po.setMcpServers(toJson(mcpServers));
        try {
            manager.upsertScope(po);
            return new SessionScopeView(kbProjects, kbTags, toolNames, mcpServers);
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

    /** 工具/server 名单宽松规整（迭代12）：trim、去空、去重保序；null 原样透传。 */
    private static List<String> normalizeLoose(List<String> raw) {
        if (raw == null) {
            return null;
        }
        return raw.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /** 三态序列化：null → null（列 NULL）；空/非空 → JSON 数组串。 */
    private static String toJson(List<String> values) {
        return values == null ? null : JSON.toJSONString(values);
    }

    /** 三态反序列化：null/空白 → null；其余按 JSON 数组解析（坏数据防御为空数组）。 */
    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (RuntimeException e) {
            log.warn("会话范围配置 JSON 解析失败，按空数组降级: {}", e.getMessage());
            return List.of();
        }
    }

    private static MemoryUnavailableException unavailable(DataAccessException e) {
        log.warn("会话服务数据访问失败（记忆服务不可用）: {}", e.getMessage());
        return new MemoryUnavailableException(ChatService.MEMORY_UNAVAILABLE_MESSAGE, e);
    }
}
