package com.dj.ai.agentchat.orchestration;

import com.dj.ai.agentchat.orchestration.frame.OrchFrame;

import java.util.function.Consumer;

/**
 * 编排过程帧桥接器（迭代5，T4）：形态复刻 {@code ToolCallBridge}——每轮对话新建一个实例，
 * 编排循环在 sdd-orchestrator 线程上 publish plan/task 帧；流式路径由 ChatController
 * 挂 sink 转发 SSE，同步路径 sink=noop。sink 调用全 try/catch（发送失败不影响编排）；
 * 流终止五条路径 {@link #detach()} 后迟到帧丢弃（AC-34）。
 */
public class OrchEventBridge {

    // 默认 noop（同步路径）；volatile 保证编排线程即时看到 detach
    private volatile Consumer<OrchFrame> sink = frame -> {
    };

    /** 挂接事件 sink（流式路径由 ChatController 调用）。 */
    public void setSink(Consumer<OrchFrame> sink) {
        this.sink = sink == null ? frame -> {
        } : sink;
    }

    /** 流终止（done/error/timeout/onCompletion/onError）后摘除 sink，后续帧丢弃。 */
    public void detach() {
        this.sink = frame -> {
        };
    }

    /** 发布帧；sink 抛任何异常都吞掉（客户端断连不影响编排循环）。 */
    public void publish(OrchFrame frame) {
        try {
            sink.accept(frame);
        } catch (Throwable t) {
            // 发送失败不影响编排
        }
    }
}
