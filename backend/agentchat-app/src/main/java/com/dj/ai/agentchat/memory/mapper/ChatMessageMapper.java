package com.dj.ai.agentchat.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 消息表 Mapper（迭代3）。成对落库直接用 {@link BaseMapper#insert}（@Transactional 内循环，
 * 同事务同连接，任一失败整体回滚，不留孤儿 user）；读取用自定义 {@code @Select} 最近 N 条。
 * Mapper 扫描随 {@code ChatMemoryConfig} 的 {@code @MapperScan} 条件收口（不标 @Mapper）。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessagePO> {

    /**
     * 取该会话最近 N 条消息，<b>时间正序</b>返回（与 InMemoryChatMemory 的 lastN 语义一致，
     * 实证 1/9）。{@code LIMIT #{n}} 为预编译参数（MySQL 8 实测可用），n 为服务端配置 int，无注入面。
     *
     * <p>迭代8 窗口语义：窗口只按 user/assistant/system 计 N 条——内层在白名单 role 内
     * 倒序取最近 N 条定窗口下界 {@code min_id}（MySQL 不允许 LIMIT 出现在 IN 子查询，
     * 故用 JOIN 派生表规避），外层取回 {@code id >= min_id} 的<b>全部角色</b>（含窗口内的
     * tool_evidence 证据行，证据不挤占对话窗口、随窗口自然淘汰）。无证据会话的所有行均在
     * 白名单内 → 结果集与旧 SQL 逐行一致（回归保证）。
     *
     * <p>迭代13（对话删除）：回放只取最近一次「清空上下文」标记点（role='context_reset'，
     * 零 DDL 标记行）之后的消息——内层窗口子查询与外层取回统一加 {@code id > resetId}。
     * 无标记会话 COALESCE=0 → {@code id > 0} 恒真，结果集与旧 SQL 逐行一致（回归保证）；
     * 标记行本身天然被排除（任何标记 id ≤ 最新标记 id）。标记点之前的消息仍在库中
     * 供界面展示，仅对模型不可见（FR-5「清空上下文但保留记录」）。
     */
    @Select("SELECT t.id, t.session_id, t.role, t.content, t.created_at FROM chat_message t "
            + "INNER JOIN ("
            + "SELECT MIN(w.id) AS min_id FROM ("
            + "SELECT id FROM chat_message "
            + "WHERE session_id = #{sessionId} AND role IN ('user','assistant','system') "
            + "AND id > (SELECT COALESCE(MAX(r.id),0) FROM chat_message r "
            + "WHERE r.session_id = #{sessionId} AND r.role = 'context_reset') "
            + "ORDER BY id DESC LIMIT #{n}"
            + ") w"
            + ") win ON t.session_id = #{sessionId} AND t.id >= win.min_id "
            + "AND t.id > (SELECT COALESCE(MAX(r2.id),0) FROM chat_message r2 "
            + "WHERE r2.session_id = #{sessionId} AND r2.role = 'context_reset') "
            + "ORDER BY t.id ASC")
    List<ChatMessagePO> selectRecent(@Param("sessionId") String sessionId, @Param("n") int n);

    /**
     * 该会话在 {@code afterId} 之后最近一条 user 消息的 id（迭代13 删单轮区间上界）；
     * 无后继 user（锚点是最后一轮）返回 {@code null}。
     */
    @Select("SELECT MIN(id) FROM chat_message "
            + "WHERE session_id = #{sessionId} AND role = 'user' AND id > #{afterId}")
    Long selectNextUserId(@Param("sessionId") String sessionId, @Param("afterId") long afterId);

    /**
     * 删除该会话 {@code [fromId, toIdExclusive)} 区间内的消息（迭代13 删单轮）：
     * 一跳覆盖 user + 区间内 tool_evidence + assistant；<b>跳过 context_reset 标记行</b>
     * （记忆边界不随删除消失，防止已清空历史静默复活进模型上下文）。
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId} "
            + "AND id >= #{fromId} AND id < #{toIdExclusive} AND role != 'context_reset'")
    int deleteRange(@Param("sessionId") String sessionId,
                    @Param("fromId") long fromId,
                    @Param("toIdExclusive") long toIdExclusive);

    /**
     * 删除该会话 {@code fromId} 及之后的全部消息（迭代13 截断重问；删单轮锚点为最后
     * 一轮时复用）；同样<b>跳过 context_reset 标记行</b>。
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId} "
            + "AND id >= #{fromId} AND role != 'context_reset'")
    int deleteFrom(@Param("sessionId") String sessionId, @Param("fromId") long fromId);

    /**
     * 清空某会话消息（ChatMemory.clear 契约实现；本期不对外暴露 HTTP，FR-19）。
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * 某会话<b>全量</b>消息按 id（时间）升序返回（迭代4 FR-6）：区别于 {@link #selectRecent}
     * 的「最近 N 条」记忆窗口语义，本方法供历史消息接口界面展示，不截断、不限条数。
     */
    @Select("SELECT id, session_id, role, content, created_at FROM chat_message "
            + "WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatMessagePO> selectAllAscending(@Param("sessionId") String sessionId);

    /**
     * 一次 IN 批量取回多个会话各自「最近一条消息」（迭代4 FR-5，AC-14 无 N+1）：
     * 内层按 session_id 分组取 MAX(id)，外层 JOIN 回行取全文。空集合由调用方（Manager）
     * 拦截不发查询，避免 {@code IN ()} 语法错误。
     *
     * <p>迭代8：内层 MAX(id) 加 role 白名单——会话预览必须跳过 tool_evidence 证据行
     * （证据仅供模型回放，用户出口不可见），否则预览变成证据文本。
     */
    @Select("<script>"
            + "SELECT m.id, m.session_id, m.role, m.content, m.created_at FROM chat_message m "
            + "INNER JOIN (SELECT session_id, MAX(id) AS max_id FROM chat_message "
            + "WHERE role IN ('user','assistant','system') AND session_id IN "
            + "<foreach collection='sessionIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "GROUP BY session_id) t ON m.id = t.max_id"
            + "</script>")
    List<ChatMessagePO> selectLatestPerSession(@Param("sessionIds") Collection<String> sessionIds);
}
