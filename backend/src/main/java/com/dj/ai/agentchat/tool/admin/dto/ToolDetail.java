package com.dj.ai.agentchat.tool.admin.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dj.ai.agentchat.tool.po.AgentToolPO;

import java.time.LocalDateTime;

/**
 * 工具详情（插入迭代 G，T10）：全字段，含 guide_md / input_schema / handler_config 全文
 * （JSON 列解析为嵌套 JSON 对象返回，非转义字符串）。
 */
public record ToolDetail(Long id,
                         String name,
                         String description,
                         JSONObject inputSchema,
                         String handlerType,
                         JSONObject handlerConfig,
                         String guideMd,
                         Boolean enabled,
                         Integer timeoutMs,
                         Integer outputMaxChars,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt) {

    /** PO → 详情；JSON 文本列解析失败时返回 null（不向管理端抛栈）。 */
    public static ToolDetail from(AgentToolPO po) {
        return new ToolDetail(
                po.getId(),
                po.getToolName(),
                po.getDescription(),
                parseJsonObject(po.getInputSchema()),
                po.getHandlerType(),
                parseJsonObject(po.getHandlerConfig()),
                po.getGuideMd(),
                po.getEnabled(),
                po.getTimeoutMs(),
                po.getOutputMaxChars(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

    private static JSONObject parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }
}
