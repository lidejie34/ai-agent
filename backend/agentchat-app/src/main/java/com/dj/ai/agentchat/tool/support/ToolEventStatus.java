package com.dj.ai.agentchat.tool.support;

/**
 * 工具事件状态（插入迭代 G）：STARTED（执行前）/ SUCCEEDED / FAILED（含 TIMEOUT）。
 * SSE 帧序列化为小写字面量（started/succeeded/failed）。
 */
public enum ToolEventStatus {
    STARTED,
    SUCCEEDED,
    FAILED;

    /** SSE event:tool 帧 status 字段的线网字面量。 */
    public String wire() {
        return name().toLowerCase();
    }
}
