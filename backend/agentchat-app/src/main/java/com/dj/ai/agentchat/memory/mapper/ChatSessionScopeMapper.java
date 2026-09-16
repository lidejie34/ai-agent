package com.dj.ai.agentchat.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.memory.po.ChatSessionScopePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 会话级范围配置表 Mapper（迭代12）。upsert 用 MySQL 原生
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}——单条 SQL 幂等，不依赖异常驱动流程；
 * 读取用 {@link BaseMapper#selectById}（行缺席=从未配置=全默认）。
 *
 * <p>不标 {@code @Mapper}：扫描随 {@code ChatMemoryConfig} 的 {@code @MapperScan}
 * 条件收口（与 ChatSessionMapper 同纪律）。
 */
public interface ChatSessionScopeMapper extends BaseMapper<ChatSessionScopePO> {

    /**
     * 幂等 upsert：四列全量覆盖（调用方已在内存合并三态，不存在部分更新歧义）；
     * updated_at 由 DB ON UPDATE 维护。
     */
    @Insert("INSERT INTO chat_session_scope(session_id, kb_projects, kb_tags, tool_names, mcp_servers) "
            + "VALUES(#{sessionId}, #{kbProjects}, #{kbTags}, #{toolNames}, #{mcpServers}) "
            + "ON DUPLICATE KEY UPDATE kb_projects = VALUES(kb_projects), kb_tags = VALUES(kb_tags), "
            + "tool_names = VALUES(tool_names), mcp_servers = VALUES(mcp_servers)")
    int upsert(ChatSessionScopePO scope);

    /**
     * 会话级联删除的配置侧（MybatisSessionManager.deleteCascade 同事务调用）。
     */
    @Delete("DELETE FROM chat_session_scope WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
