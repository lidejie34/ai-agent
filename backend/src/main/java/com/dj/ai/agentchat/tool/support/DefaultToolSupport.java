package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认工具挂载实现（插入迭代 G，T8）：从 {@link ToolRegistry} 取当前启用工具快照，
 * 为空（DB 故障降级/零启用行）返回 null；非空则创建请求级 {@link ToolCallBridge}
 * 与 toolContext（sessionId/requestId/bridge）。
 *
 * <p>requestId 在挂载时（Flux.defer 之外）生成，流式重订阅/重试复用同一挂载，
 * 保证 dedupKey 稳定（S4 幂等）。
 */
@Slf4j
public class DefaultToolSupport implements ToolSupport {

    /** ToolContext 键：会话 ID（无状态为 null）。 */
    public static final String CTX_SESSION_ID = "sessionId";
    /** ToolContext 键：请求 ID（挂载时生成的 UUID）。 */
    public static final String CTX_REQUEST_ID = "requestId";
    /** ToolContext 键：工具事件桥。 */
    public static final String CTX_TOOL_BRIDGE = "toolBridge";

    private final ToolRegistry registry;

    public DefaultToolSupport(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolMount mountTools(String sessionId) {
        List<ToolCallback> callbacks = registry.toolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            // 零工具：调用方不触发 .tools()，请求与迭代 F 逐字节等价（AC-63）
            return null;
        }
        ToolCallBridge bridge = new ToolCallBridge();
        Map<String, Object> context = new HashMap<>();
        // Spring AI toolContext(Map) 经 Assert.noNullElements 拒绝 null 值——
        // 无状态会话 sessionId=null 时不放该键（DbToolCallback 按缺失读 null）
        if (sessionId != null && !sessionId.isBlank()) {
            context.put(CTX_SESSION_ID, sessionId);
        }
        context.put(CTX_REQUEST_ID, UUID.randomUUID().toString());
        context.put(CTX_TOOL_BRIDGE, bridge);
        log.debug("对话工具挂载: sessionId={}, 工具数={}", sessionId, callbacks.size());
        return new ToolMount(List.copyOf(callbacks), context, bridge);
    }
}
