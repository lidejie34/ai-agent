package com.dj.ai.agentchat.tool.dto;

import com.dj.ai.agentchat.tool.support.ToolEvent;

/**
 * SSE {@code event:tool} 帧载荷（插入迭代 G，T9）：随工具生命周期推给前端，
 * 前端按 {@code callId} upsert 折叠块（started/succeeded/failed 三态）。
 *
 * <p>fastjson2 默认省略 null 字段：started 帧无 {@code durationMs}/{@code error}；
 * succeeded 帧无 {@code error}。序列化经 fastjson2 HttpMessageConverter
 * （StringHttpMessageConverter 顺序红线由既有 FastJsonWebConfig 保证）。
 *
 * @param callId     幂等键 requestId|toolName|sha1(参数JSON)
 * @param tool       工具名
 * @param arguments  入参摘要（已脱敏 + 截断 500 字）
 * @param status     started / succeeded / failed
 * @param durationMs 终态耗时（毫秒）；started 为 null
 * @param error      失败原因（脱敏截断）；成功/started 为 null
 */
public record ToolEventFrame(String callId,
                             String tool,
                             String arguments,
                             String status,
                             Long durationMs,
                             String error) {

    public static ToolEventFrame from(ToolEvent event) {
        return new ToolEventFrame(event.callId(),
                event.toolName(),
                event.arguments(),
                event.status().wire(),
                event.durationMs(),
                event.error());
    }
}
