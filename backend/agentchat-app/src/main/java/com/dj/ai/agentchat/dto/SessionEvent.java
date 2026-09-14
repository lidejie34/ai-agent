package com.dj.ai.agentchat.dto;

/**
 * SSE {@code event:session} 的 data 载荷（迭代3 FR-5）：记忆路径（新建/续接）在首个
 * {@code event:message} 帧之前发送，回传本轮会话 ID；无状态路径不发该事件。
 *
 * <p>迭代9（FR-1.4）：新增可空第二字段 {@code traceId}——开启可观测性时回传本请求
 * traceId；关闭态恒 null，fastjson2 默认不序列化 null 字段 → 输出
 * {@code {"sessionId":"..."} 与迭代8 逐字节一致（SessionEventJsonTest 钉死）。
 * 单参紧凑构造保留（既有调用点/测试零改动）。
 *
 * @param sessionId 服务端确认的会话 ID（新建态为服务端生成的 UUID，续接态为请求回显）
 * @param traceId   本请求 traceId（可空；null 不序列化）
 */
public record SessionEvent(String sessionId, String traceId) {

    /** 迭代8 兼容构造：traceId=null（关闭态帧字节不变）。 */
    public SessionEvent(String sessionId) {
        this(sessionId, null);
    }
}
