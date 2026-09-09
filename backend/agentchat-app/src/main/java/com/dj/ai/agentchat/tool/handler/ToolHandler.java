package com.dj.ai.agentchat.tool.handler;

import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.spi.ToolExecutionContext;
import com.dj.ai.agentchat.tool.spi.ToolExecutionResult;
import com.dj.ai.agentchat.tool.registry.HandlerType;

import java.util.Map;

/**
 * 工具处理器扩展点（插入迭代 G）：按 {@link HandlerType} 路由。
 * 新增处理器类型 = 实现本接口 + 在 ToolRuntimeConfig 注册 bean。
 */
public interface ToolHandler {

    /** 该处理器承接的类型。 */
    HandlerType type();

    /**
     * 管理端校验 handler_config（创建/修改时）；非法抛 InvalidChatRequestException（400）。
     * 装载路径同样调用，失败被包装为 SkippableToolException 跳过该行。
     */
    void validateConfig(String handlerConfigJson);

    /**
     * 执行工具；入参为模型 JSON 解析后的 Map。实现内全捕获，<b>永不抛 RuntimeException</b>
     * （DefaultToolCallingManager 只兜底 ToolExecutionException，其余上浮会致整轮对话失败）。
     */
    ToolExecutionResult execute(AgentToolPO tool, Map<String, Object> args, ToolExecutionContext ctx);
}
