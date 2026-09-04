package com.dj.ai.agentchat.tool.callback;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.handler.ToolHandlerRouter;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * PO → {@link DbToolCallback} 工厂（插入迭代 G）：装载单行时做全部「坏行」校验，
 * 失败抛 {@link SkippableToolException} 供注册中心跳过 + WARN（AC-15/16），
 * 不影响其余工具行。
 *
 * <p>校验项：handler_type 可解析且有对应处理器（HTTP/SCRIPT_DB 预留 → 跳过）、
 * handler_config 为合法 JSON 对象且通过处理器自身校验（BUILTIN bean 存在、
 * SCRIPT 脚本文件在白名单目录内真实存在）、name/description/input_schema 非空白
 * （DefaultToolDefinition 构造断言）。
 */
@Slf4j
public class ToolCallbackFactory {

    private final ToolHandlerRouter router;
    private final ToolAuditService auditService;
    private final SecretRedactor redactor;
    private final ExecutorService toolExecutor;
    private final ToolProperties properties;

    public ToolCallbackFactory(ToolHandlerRouter router,
                               ToolAuditService auditService,
                               SecretRedactor redactor,
                               ExecutorService toolExecutor,
                               ToolProperties properties) {
        this.router = router;
        this.auditService = auditService;
        this.redactor = redactor;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 构建回调；坏行抛 {@link SkippableToolException}（调用方跳过该行）。
     */
    public ToolCallback build(AgentToolPO po) {
        HandlerType type = parseHandlerType(po.getHandlerType());
        // 预留类型/无处理器实现 → 路由抛 SkippableToolException
        ToolHandler handler = router.route(type);

        JSONObject config = parseConfig(po.getHandlerConfig());
        if (config == null) {
            throw new SkippableToolException("handler_config 不是合法 JSON 对象: toolName=" + po.getToolName());
        }
        try {
            // 处理器自身校验：BUILTIN bean 存在性 / SCRIPT 脚本文件存在性等
            handler.validateConfig(po.getHandlerConfig());
        } catch (InvalidChatRequestException e) {
            throw new SkippableToolException(
                    "handler_config 校验失败: toolName=" + po.getToolName() + ", 原因=" + e.getMessage());
        }

        try {
            // 失败 fast：三字符串非空白断言（逐字来自 DB，AC-10）
            DefaultToolDefinition.builder()
                    .name(po.getToolName())
                    .description(po.getDescription())
                    .inputSchema(po.getInputSchema())
                    .build();
        } catch (RuntimeException e) {
            throw new SkippableToolException(
                    "工具定义非法（name/description/input_schema 不能为空）: toolName=" + po.getToolName());
        }

        return new DbToolCallback(po, router, auditService, redactor, toolExecutor, properties);
    }

    private HandlerType parseHandlerType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkippableToolException("handler_type 为空");
        }
        try {
            return HandlerType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkippableToolException("未知 handler_type: " + raw);
        }
    }

    private JSONObject parseConfig(String json) {
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            throw new SkippableToolException("handler_config 非法 JSON: " + e.getMessage());
        }
    }
}
