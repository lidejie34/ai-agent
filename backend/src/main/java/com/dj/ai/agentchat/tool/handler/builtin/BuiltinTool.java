package com.dj.ai.agentchat.tool.handler.builtin;

import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;

import java.util.Map;

/**
 * 内置工具扩展点（插入迭代 G）：新增内置工具 = 实现本接口 + 在 agent_tool 插一行
 * （handler_type=BUILTIN，handler_config={@code {"bean":"<key>"}}），无需发版。
 *
 * <p>实现方约束：内部全捕获，{@link #execute(Map, ToolExecutionContext)} 永不抛
 * RuntimeException——参数非法返回 {@code failed("INVALID_ARGS", 原因)}，
 * 其余失败返回对应错误码的结构化结果。
 */
public interface BuiltinTool {

    /**
     * 与 handler_config.bean 对应的唯一键（DB 注册行 handler_config 中引用）。
     */
    String key();

    /**
     * 执行内置逻辑。
     *
     * @param args 模型入参（已由 DbToolCallback 解析为 JSON 对象）
     * @param ctx  执行上下文（sessionId/requestId/超时/输出上限）
     * @return 成功为 Markdown 文本；失败为错误码 + 可读原因
     */
    ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext ctx);
}
