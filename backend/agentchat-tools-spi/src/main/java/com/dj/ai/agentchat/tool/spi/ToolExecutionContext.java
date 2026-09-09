package com.dj.ai.agentchat.tool.spi;

/**
 * 单次工具执行上下文（插入迭代 G）：由 DbToolCallback 在调用处理器时组装，
 * 从 ToolContext（requestId/sessionId）与工具行配置派生。
 *
 * @param sessionId      会话 ID；无状态对话为 null
 * @param requestId      请求级 ID（Flux.defer 外生成，重订阅稳定；幂等键组成部分）
 * @param timeoutMs      本次执行总预算毫秒（表行值，缺省取 app.tools.default-timeout-ms）
 * @param outputMaxChars 回传模型结果最大字符数（表行值，缺省取 default-output-max-chars）
 */
public record ToolExecutionContext(String sessionId,
                                   String requestId,
                                   long timeoutMs,
                                   int outputMaxChars) {
}
