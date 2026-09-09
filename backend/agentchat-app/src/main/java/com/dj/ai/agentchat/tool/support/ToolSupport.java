package com.dj.ai.agentchat.tool.support;

/**
 * 对话链路工具挂载扩展点（插入迭代 G，T8）：ChatService 经 ObjectProvider 可选注入
 * （工具开关关闭时无 bean → null → 零挂载，与迭代 F 逐字节等价，AC-63）。
 */
public interface ToolSupport {

    /**
     * 为一次对话请求（同步或流式）装配工具挂载。
     *
     * @param sessionId 会话 ID；无状态对话为 null（透传到 toolContext，供审计关联）
     * @return 工具挂载；无启用工具（空集/DB 故障降级）时返回 null，调用方据此跳过 .tools()
     */
    ToolMount mountTools(String sessionId);
}
