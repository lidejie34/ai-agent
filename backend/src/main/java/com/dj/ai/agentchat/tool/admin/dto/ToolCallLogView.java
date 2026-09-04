package com.dj.ai.agentchat.tool.admin.dto;

import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;

import java.time.LocalDateTime;

/**
 * 工具调用审计视图（插入迭代 G，T10）：字段同审计表；工具行删除后审计行保留（AC-47），
 * toolName 为字符串留存。
 */
public record ToolCallLogView(Long id,
                              String callId,
                              String toolName,
                              String handlerType,
                              String sessionId,
                              String inputSummary,
                              String status,
                              Long durationMs,
                              String errorMessage,
                              Integer resultChars,
                              LocalDateTime createdAt) {

    public static ToolCallLogView from(AgentToolCallLogPO po) {
        return new ToolCallLogView(
                po.getId(),
                po.getCallId(),
                po.getToolName(),
                po.getHandlerType(),
                po.getSessionId(),
                po.getInputSummary(),
                po.getStatus(),
                po.getDurationMs(),
                po.getErrorMessage(),
                po.getResultChars(),
                po.getCreatedAt());
    }
}
