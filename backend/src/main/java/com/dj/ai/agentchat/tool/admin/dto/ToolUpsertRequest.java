package com.dj.ai.agentchat.tool.admin.dto;

import com.alibaba.fastjson2.JSONObject;

/**
 * 工具新增/修改请求体（插入迭代 G，T10）。
 *
 * <p>{@code inputSchema} / {@code handlerConfig} 为 JSON 对象（fastjson2 record 绑定
 * 直接映射 {@link JSONObject}），落库时序列化为 JSON 文本；{@code name} 仅 POST 生效
 * （PUT/PATCH 携带且与现名不同 → 400）；PATCH 语义下 null 字段表示「不修改」。
 *
 * @param name            工具名（仅 POST 必填）；正则 ^[a-z][a-z0-9_]{1,63}$
 * @param description     工具描述（非空白、≤2000）
 * @param inputSchema     入参 JSON Schema 对象（须含 type 或 properties）
 * @param handlerType     BUILTIN / SCRIPT（HTTP/SCRIPT_DB 拒绝）
 * @param handlerConfig   处理器配置：BUILTIN {"bean":"..."} / SCRIPT {"script":"文件名"}
 * @param guideMd         SKILL.md 式操作指南全文（可空）
 * @param enabled         是否启用（缺省 true）
 * @param timeoutMs       单次超时毫秒（1-60000，空则默认 30000）
 * @param outputMaxChars  结果最大字符数（100-100000，空则默认 8000）
 */
public record ToolUpsertRequest(String name,
                                String description,
                                JSONObject inputSchema,
                                String handlerType,
                                JSONObject handlerConfig,
                                String guideMd,
                                Boolean enabled,
                                Integer timeoutMs,
                                Integer outputMaxChars) {
}
