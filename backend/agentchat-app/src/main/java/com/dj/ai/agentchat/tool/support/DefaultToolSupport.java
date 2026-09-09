package com.dj.ai.agentchat.tool.support;

import com.dj.ai.agentchat.tool.mcp.callback.McpToolProvider;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认工具挂载实现（插入迭代 G；迭代4 T4 扩展 MCP 合并）：
 * DB 工具快照（{@link ToolRegistry}）在前、MCP 工具快照（{@link McpToolProvider}，
 * 可空——子开关关闭/依赖缺失时为 null，行为与迭代 G 逐字节一致）在后合并为一次
 * {@code .tools(...)} 挂载；同名冲突跳过 MCP 并 WARN（D9/AC-14：DB 工具优先）。
 *
 * <p>合并为空（DB 故障降级 + 零 READY server）返回 null；非空则创建请求级
 * {@link ToolCallBridge} 与 toolContext（sessionId/requestId/bridge）——DB 与 MCP
 * 回调共享同一 context（同一 bridge 发帧、同一 requestId 幂等域）。
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
    /** MCP 回调供给；null 表示 MCP 子开关关闭/未装配（纯 DB 行为，AC-3）。 */
    private final McpToolProvider mcpToolProvider;

    public DefaultToolSupport(ToolRegistry registry) {
        this(registry, null);
    }

    public DefaultToolSupport(ToolRegistry registry, McpToolProvider mcpToolProvider) {
        this.registry = registry;
        this.mcpToolProvider = mcpToolProvider;
    }

    @Override
    public ToolMount mountTools(String sessionId) {
        List<ToolCallback> merged = mergeCallbacks();
        if (merged.isEmpty()) {
            // 零工具：调用方不触发 .tools()，请求与迭代 F 逐字节等价（AC-63）
            return null;
        }
        ToolCallBridge bridge = new ToolCallBridge();
        Map<String, Object> context = new HashMap<>();
        // Spring AI toolContext(Map) 经 Assert.noNullElements 拒绝 null 值——
        // 无状态会话 sessionId=null 时不放该键（回调按缺失读 null）
        if (sessionId != null && !sessionId.isBlank()) {
            context.put(CTX_SESSION_ID, sessionId);
        }
        context.put(CTX_REQUEST_ID, UUID.randomUUID().toString());
        context.put(CTX_TOOL_BRIDGE, bridge);
        log.debug("对话工具挂载: sessionId={}, 工具数={}", sessionId, merged.size());
        return new ToolMount(List.copyOf(merged), context, bridge);
    }

    /**
     * DB 工具在前、MCP 工具在后；按工具名去重（LinkedHashSet 保序），
     * MCP 侧与 DB（或先入集合）同名时跳过 + WARN（D9/AC-14）。
     */
    private List<ToolCallback> mergeCallbacks() {
        List<ToolCallback> dbCallbacks = registry.toolCallbacks();
        List<ToolCallback> merged = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (dbCallbacks != null) {
            for (ToolCallback cb : dbCallbacks) {
                String name = callbackName(cb);
                if (name == null || names.add(name)) {
                    merged.add(cb);
                }
            }
        }

        if (mcpToolProvider != null) {
            ToolCallback[] mcpCallbacks;
            try {
                mcpCallbacks = mcpToolProvider.getToolCallbacks();
            } catch (Throwable t) {
                // 供给侧异常绝不影响 DB 工具挂载
                log.warn("MCP 工具快照获取异常，本次仅挂载 DB 工具: {}", t.getMessage());
                mcpCallbacks = new ToolCallback[0];
            }
            for (ToolCallback cb : mcpCallbacks) {
                String name = callbackName(cb);
                if (name == null || names.add(name)) {
                    merged.add(cb);
                } else {
                    log.warn("MCP 工具与已挂载工具同名冲突，跳过 MCP 工具（D9，DB 工具优先）: {}", name);
                }
            }
        }
        return merged;
    }

    /** 工具名提取（null/异常安全：个别回调无 definition 时不阻断挂载）。 */
    private static String callbackName(ToolCallback cb) {
        try {
            return cb.getToolDefinition() == null ? null : cb.getToolDefinition().name();
        } catch (Throwable t) {
            return null;
        }
    }
}
