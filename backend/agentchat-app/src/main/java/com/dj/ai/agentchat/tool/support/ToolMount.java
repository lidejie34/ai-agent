package com.dj.ai.agentchat.tool.support;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * 一次对话请求的工具挂载（插入迭代 G，T8）：
 *
 * @param callbacks   本请求可用的工具回调（注册中心快照子集；非空）
 * @param toolContext 透传给框架的 ToolContext 内容：sessionId（可 null）、
 *                    requestId（挂载时生成，Flux.defer 重订阅保持稳定）、toolBridge
 * @param bridge      工具事件桥（SSE event:tool 帧的 sink 由控制器在订阅后设置）
 */
public record ToolMount(List<ToolCallback> callbacks,
                        Map<String, Object> toolContext,
                        ToolCallBridge bridge) {
}
