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
     * 取该会话最近 N 条消息，<b>时间正序</b>返回：内层按 id DESC 取最近 N 条，
     * 外层再按 id ASC 正序（与 InMemoryChatMemory 的 lastN 语义一致，实证 1/9）。
     * {@code LIMIT #{n}} 为预编译参数（MySQL 8 实测可用），n 为服务端配置 int，无注入面。
     */
    @Select("SELECT id, session_id, role, content, created_at FROM "
            + "(SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{n}) t "
            + "ORDER BY t.id ASC")
    List<ChatMessagePO> selectRecent(@Param("sessionId") String sessionId, @Param("n") int n);

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
     */
    @Select("<script>"
            + "SELECT m.id, m.session_id, m.role, m.content, m.created_at FROM chat_message m "
            + "INNER JOIN (SELECT session_id, MAX(id) AS max_id FROM chat_message "
            + "WHERE session_id IN "
            + "<foreach collection='sessionIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "GROUP BY session_id) t ON m.id = t.max_id"
            + "</script>")
    List<ChatMessagePO> selectLatestPerSession(@Param("sessionIds") Collection<String> sessionIds);
}
