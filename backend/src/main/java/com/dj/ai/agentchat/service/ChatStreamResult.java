package com.dj.ai.agentchat.service;

import reactor.core.publisher.Flux;

/**
 * 流式问答结果（迭代3）：记忆阶段在 service 同步段完成后返回——
 * 控制器先据 {@code sessionId} 决定是否发 {@code event:session}，再起心跳/订阅 {@code chunks}。
 *
 * @param sessionId 会话 ID；{@code null} 表示无状态（控制器不发 session 事件）
 * @param chunks    模型输出片段 Flux（记忆失败在装配该 Flux 前已同步抛出，不会走到此处）
 */
public record ChatStreamResult(String sessionId, Flux<String> chunks) {

    public static ChatStreamResult stateless(Flux<String> chunks) {
        return new ChatStreamResult(null, chunks);
    }
}
