package com.dj.ai.agentchat.dto.session;

import java.time.LocalDateTime;

/**
 * 「清空上下文」标记视图（迭代13 FR-5）：插入 context_reset 标记行后返回，
 * 前端据 id 就地渲染分隔线，无需整页重载。
 *
 * @param id        标记行库主键
 * @param createdAt 标记创建时间
 */
public record ContextResetView(Long id, LocalDateTime createdAt) {
}
