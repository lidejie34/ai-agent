package com.dj.ai.agentchat.tool.admin.dto;

import java.time.LocalDateTime;

/**
 * 工具列表项（插入迭代 G，T10）：<b>不含 guide_md/input_schema/handler_config 全文</b>，
 * 仅带 {@code guideLength}（指南长度，0 表示无指南），防止列表接口拖出大字段（AC-42）。
 */
public record ToolListItem(Long id,
                           String name,
                           String description,
                           String handlerType,
                           Boolean enabled,
                           Integer timeoutMs,
                           Integer outputMaxChars,
                           int guideLength,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
}
