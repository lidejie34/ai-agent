package com.dj.ai.agentchat.service;

import com.dj.ai.agentchat.orchestration.OrchEventBridge;
import com.dj.ai.agentchat.tool.support.ToolCallBridge;
import reactor.core.publisher.Flux;

/**
 * 流式问答结果（迭代3；插入迭代 G 增 {@code toolBridge}；迭代5 增 {@code orchBridge}）：
 * 记忆阶段在 service 同步段完成后返回——控制器先据 {@code sessionId} 决定是否发
 * {@code event:session}，再起心跳/订阅 {@code chunks}。
 *
 * @param sessionId  会话 ID；{@code null} 表示无状态（控制器不发 session 事件）
 * @param chunks     模型输出片段 Flux（记忆失败在装配该 Flux 前已同步抛出，不会走到此处）
 * @param toolBridge 工具事件桥（插入迭代 G）；本次请求无工具挂载时为 null。
 *                   控制器订阅后 setSink 把工具生命周期事件推为 event:tool 帧，
 *                   流终止时 detach（共 5 条终止路径）
 * @param orchBridge 编排过程帧桥（迭代5）；仅 app.sdd.enabled=true 且本次走编排路径时非 null。
 *                   控制器订阅后 setSink 把 plan/task 帧推为 event:plan/event:task，
 *                   流终止时与工具桥一同 detach；普通路径为 null（迭代4 字节兼容，AC-1/AC-2）
 */
public record ChatStreamResult(String sessionId, Flux<String> chunks,
                               ToolCallBridge toolBridge, OrchEventBridge orchBridge) {

    /** 迭代 G 兼容构造：无编排桥（普通路径/既有测试使用）。 */
    public ChatStreamResult(String sessionId, Flux<String> chunks, ToolCallBridge toolBridge) {
        this(sessionId, chunks, toolBridge, null);
    }

    /** 无工具/无编排挂载的兼容构造（迭代 F 及既有测试/无工具路径使用）。 */
    public ChatStreamResult(String sessionId, Flux<String> chunks) {
        this(sessionId, chunks, null, null);
    }

    public static ChatStreamResult stateless(Flux<String> chunks) {
        return new ChatStreamResult(null, chunks, null, null);
    }
}
