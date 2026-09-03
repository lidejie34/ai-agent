package com.dj.ai.agentchat.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 会话表 Mapper（迭代3）。自定义注解 SQL 为主：INSERT IGNORE 幂等建会话
 * （单条 SQL 幂等，不依赖异常驱动流程；MySQL 8 实测首插 1 行/重复 0 行、不抛错）。
 *
 * <p>不标 {@code @Mapper}：Mapper 扫描统一由 {@code ChatMemoryConfig} 上的
 * {@code @MapperScan} 随 {@code app.chat.memory.enabled} 条件收口（enabled=false 时
 * MyBatis 自动扫描也找不到 @Mapper 接口，记忆 Mapper bean 不装配）。
 */
public interface ChatSessionMapper extends BaseMapper<ChatSessionPO> {

    /**
     * 幂等建会话：title 恒 NULL，时间戳走 DB 默认值；返回行数不依赖（重复为 0）。
     */
    @Insert("INSERT IGNORE INTO chat_session(session_id) VALUES(#{sessionId})")
    int insertIgnoreSession(@Param("sessionId") String sessionId);
}
