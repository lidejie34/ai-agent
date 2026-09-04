package com.dj.ai.agentchat.tool.handler.builtin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BUILTIN 处理器（插入迭代 G）：按 handler_config 的 {@code bean} 键路由到
 * Spring 容器中的 {@link BuiltinTool} 实现（按 {@link BuiltinTool#key()} 归集，
 * 新增内置工具不发版）。
 *
 * <p>装载期 {@link #validateConfig(String)} 校验 bean 键非空且实现存在
 * （失败抛 {@link InvalidChatRequestException}，由 ToolCallbackFactory 转
 * SkippableToolException 跳过坏行）；运行期 bean 缺失/实现逃逸均转结构化失败，
 * 绝不上浮 RuntimeException（AC-56）。
 */
@Slf4j
public class BuiltinToolHandler implements ToolHandler {

    /** key → 内置工具实现（TreeMap 保证遍历顺序稳定，便于日志/测试）。 */
    private final Map<String, BuiltinTool> tools;

    public BuiltinToolHandler(List<BuiltinTool> builtinTools) {
        Map<String, BuiltinTool> map = new TreeMap<>();
        if (builtinTools != null) {
            for (BuiltinTool tool : builtinTools) {
                map.put(tool.key(), tool);
            }
        }
        this.tools = Map.copyOf(map);
    }

    @Override
    public HandlerType type() {
        return HandlerType.BUILTIN;
    }

    @Override
    public void validateConfig(String handlerConfigJson) {
        String bean = parseBeanKey(handlerConfigJson);
        if (bean == null || bean.isBlank()) {
            throw new InvalidChatRequestException("BUILTIN 工具 handler_config 缺少非空 bean 键");
        }
        if (!tools.containsKey(bean)) {
            throw new InvalidChatRequestException("BUILTIN 工具 bean 不存在: " + bean);
        }
    }

    @Override
    public ToolExecutionResult execute(AgentToolPO tool, Map<String, Object> args,
                                       ToolExecutionContext ctx) {
        String bean;
        try {
            bean = parseBeanKey(tool.getHandlerConfig());
        } catch (InvalidChatRequestException e) {
            return ToolExecutionResult.failed("INVALID_ARGS", e.getMessage());
        }
        BuiltinTool target = bean == null ? null : tools.get(bean);
        if (target == null) {
            // 装载后实现被撤（极小概率）：结构化失败，不炸对话
            log.warn("BUILTIN 工具运行期 bean 缺失: toolName={}, bean={}", tool.getToolName(), bean);
            return ToolExecutionResult.failed("TOOL_NOT_AVAILABLE", "内置工具不可用: bean=" + bean);
        }
        try {
            return target.execute(args == null ? Map.of() : args, ctx);
        } catch (Throwable t) {
            // 内置工具承诺全捕获；此为最终兜底（AC-56）
            log.error("内置工具执行逃逸异常已兜底: toolName={}, bean={}",
                    tool.getToolName(), bean, t);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "工具执行异常: " + msg);
        }
    }

    /** 解析 handler_config 的 bean 键；非法 JSON 或缺失抛 InvalidChatRequestException。 */
    private String parseBeanKey(String handlerConfigJson) {
        JSONObject config;
        try {
            config = JSON.parseObject(handlerConfigJson);
        } catch (Exception e) {
            throw new InvalidChatRequestException("BUILTIN 工具 handler_config 非法 JSON: " + e.getMessage());
        }
        if (config == null) {
            throw new InvalidChatRequestException("BUILTIN 工具 handler_config 不是 JSON 对象");
        }
        String bean = config.getString("bean");
        return bean == null ? null : bean.trim();
    }
}
