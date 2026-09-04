package com.dj.ai.agentchat.tool.audit;

import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 工具调用审计落库（插入迭代 G，AC-37/38）：best-effort 写入 agent_tool_call_log。
 *
 * <p>任何 DB 异常都不影响对话：{@link DuplicateKeyException}（call_id 唯一索引兜底命中，
 * 重试重订阅的重复调用）仅 debug 日志；其余 {@link DataAccessException} 仅
 * {@code log.error("审计落库失败...")}。调用方在工具结果回传后写入，失败不重试。
 */
@Slf4j
public class ToolAuditService {

    private final AgentToolCallLogMapper callLogMapper;

    public ToolAuditService(AgentToolCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    /**
     * best-effort 插入审计行；失败仅日志，不抛异常。
     */
    public void record(AgentToolCallLogPO po) {
        try {
            callLogMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 幂等兜底命中（call_id 唯一索引）：重试场景的重复调用，debug 即可
            log.debug("审计 call_id 冲突已忽略（幂等兜底）: toolName={}, callId={}",
                    po.getToolName(), po.getCallId());
        } catch (DataAccessException e) {
            // 审计失败绝不影响对话（AC-38）
            log.error("审计落库失败（不影响对话）: toolName={}, callId={}, 原因={}",
                    po.getToolName(), po.getCallId(), e.getMessage());
        }
    }
}
