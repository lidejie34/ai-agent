package com.dj.ai.agentchat.memory;

import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;

import java.util.Collection;
import java.util.List;

/**
 * 会话级数据操作抽象（迭代4）：会话列表/历史消息/级联删除/重命名。
 *
 * <p>与 {@link ConversationStore}（Spring AI 消息记忆 add/get/clear + 建会话）分离：
 * 本接口面向会话管理 REST 接口的<b>读/管理</b>场景，PO 仍封装在 memory 包内，
 * 由 service 层映射为 DTO。实现：{@code MybatisSessionManager}（MyBatis-Plus + MySQL）。
 * bean 随 {@code ChatMemoryConfig} 的 {@code app.chat.memory.enabled} 条件装配——
 * 与 ConversationStore 同生灭；enabled=false 时 service 经 ObjectProvider 拿到 null → 400。
 */
public interface SessionManager {

    /**
     * 全部会话，按 {@code updated_at} 倒序（最近活跃在前）。
     */
    List<ChatSessionPO> listSessions();

    /**
     * 一次 IN 批量取回给定会话各自「最近一条消息」（组内 MAX(id)），无 N+1；
     * 空集合返回空列表且不发查询（避免 {@code IN ()} 语法错误）。
     */
    List<ChatMessagePO> latestMessages(Collection<String> sessionIds);

    /**
     * 某会话全部消息，按 id（时间）<b>升序</b>、全文不截断（FR-6，区别于记忆窗口的最近 N 条）。
     */
    List<ChatMessagePO> listMessagesAscending(String sessionId);

    /**
     * 按主键查会话行；不存在返回 {@code null}（service 据此 404）。
     */
    ChatSessionPO findSession(String sessionId);

    /**
     * 级联删除：同事务先删消息后删会话行，不留孤儿消息（FR-7）。
     */
    void deleteCascade(String sessionId);

    /**
     * 重命名：仅更新 title（trim 后值由 service 校验）；updated_at 由 MySQL ON UPDATE 维护。
     * 返回更新后的会话行（含最新 updated_at）。
     */
    ChatSessionPO rename(String sessionId, String title);

    /**
     * 按库主键查消息并校验会话归属（迭代13）：不存在或不属于该会话返回 {@code null}
     * （service 据此 404，防跨会话删除）。
     */
    ChatMessagePO findMessage(String sessionId, long messageId);

    /**
     * 该会话 {@code afterId} 之后最近一条 user 消息的 id（迭代13 删单轮区间上界）；
     * 无后继 user 返回 {@code null}。
     */
    Long findNextUserId(String sessionId, long afterId);

    /**
     * 删除 {@code [fromId, toIdExclusive)} 区间内消息（迭代13 删单轮）；
     * 跳过 context_reset 标记行。返回删除行数。
     */
    int deleteMessageRange(String sessionId, long fromId, long toIdExclusive);

    /**
     * 删除 {@code fromId} 及之后全部消息（迭代13 截断重问）；跳过 context_reset 标记行。
     * 返回删除行数。
     */
    int deleteMessagesFrom(String sessionId, long fromId);

    /**
     * 插入「清空上下文」标记行（迭代13 FR-5，role='context_reset'，零 DDL）；
     * 返回含自增 id 与 created_at 的完整行。
     */
    ChatMessagePO insertContextReset(String sessionId);

    /**
     * 查会话级范围配置（迭代12）：行缺席返回 {@code null}（= 从未配置 = 全默认）。
     */
    ChatSessionScopePO findScope(String sessionId);

    /**
     * 幂等 upsert 会话级范围配置（迭代12）：四列全量覆盖，三态语义由 service 层保证。
     */
    void upsertScope(ChatSessionScopePO scope);
}
