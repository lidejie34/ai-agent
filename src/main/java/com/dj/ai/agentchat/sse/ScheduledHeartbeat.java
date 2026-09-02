package com.dj.ai.agentchat.sse;

/**
 * 一次 SSE 流的心跳句柄：模型片段到达时 {@link #reset()} 重新计时；
 * 流结束（完成/出错/超时/客户端断连）时 {@link #cancel()} 停止，保证不泄漏、不在结束后发帧。
 * 实现需保证 cancel 幂等、cancel 后 reset 不再调度。
 */
public interface ScheduledHeartbeat {

    /** 取消当前调度并按间隔重新计时（模型有输出，空闲计时归零）。 */
    void reset();

    /** 永久停止心跳（幂等；停止后不再发送任何帧）。 */
    void cancel();
}
