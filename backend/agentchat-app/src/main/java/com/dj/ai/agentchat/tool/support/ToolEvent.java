package com.dj.ai.agentchat.tool.support;

/**
 * 工具生命周期事件（插入迭代 G）：经 {@link ToolCallBridge} 从工具执行线程
 * 桥接到 SSE event:tool 帧。
 *
 * @param callId     幂等键 requestId|toolName|sha1(参数JSON)，前端按 callId upsert 折叠块
 * @param toolName   工具名
 * @param arguments  入参摘要（脱敏 + 截断 500 字）
 * @param status     started / succeeded / failed
 * @param durationMs 终态事件的执行耗时；started 事件为 null
 * @param error      失败原因（脱敏截断，无堆栈）；成功/started 为 null
 */
public record ToolEvent(String callId,
                        String toolName,
                        String arguments,
                        ToolEventStatus status,
                        Long durationMs,
                        String error) {

    public static ToolEvent started(String callId, String toolName, String arguments) {
        return new ToolEvent(callId, toolName, arguments, ToolEventStatus.STARTED, null, null);
    }

    public static ToolEvent terminal(String callId, String toolName, String arguments,
                                     boolean success, long durationMs, String error) {
        return new ToolEvent(callId, toolName, arguments,
                success ? ToolEventStatus.SUCCEEDED : ToolEventStatus.FAILED,
                durationMs, success ? null : error);
    }
}
