package com.dj.ai.agentchat.tool.admin.dto;

import java.util.List;

/**
 * 通用分页结果（插入迭代 G，T10）：审计查询返回
 * {@code PageResult<ToolCallLogView>}，page 为 0 基页码（与请求参数一致）。
 */
public record PageResult<T>(List<T> content, long total, int page, int size) {
}
