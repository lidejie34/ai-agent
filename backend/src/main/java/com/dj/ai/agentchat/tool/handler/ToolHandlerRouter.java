package com.dj.ai.agentchat.tool.handler;

import com.dj.ai.agentchat.tool.callback.SkippableToolException;
import com.dj.ai.agentchat.tool.registry.HandlerType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 处理器路由（插入迭代 G）：Spring 注入全部 {@link ToolHandler} 实现，按
 * {@link HandlerType} 索引；无对应实现的类型（HTTP / SCRIPT_DB 预留）抛
 * {@link SkippableToolException} 供注册中心跳过（AC-16）。
 */
public class ToolHandlerRouter {

    private final Map<HandlerType, ToolHandler> handlers = new EnumMap<>(HandlerType.class);

    public ToolHandlerRouter(List<ToolHandler> handlerList) {
        for (ToolHandler handler : handlerList) {
            handlers.put(handler.type(), handler);
        }
    }

    /** 路由；无对应处理器抛 SkippableToolException（装载跳过）。 */
    public ToolHandler route(HandlerType type) {
        ToolHandler handler = handlers.get(type);
        if (handler == null) {
            throw new SkippableToolException("处理器类型尚未实现或无对应处理器: " + type);
        }
        return handler;
    }

    /** 是否存在该类型的处理器（管理端校验用）。 */
    public boolean supports(HandlerType type) {
        return handlers.containsKey(type);
    }
}
