package com.dj.ai.agentchat.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
