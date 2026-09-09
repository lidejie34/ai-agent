package com.dj.ai.agentchat.dto.session;

import java.time.LocalDateTime;

/**
 * 会话列表项（迭代4 FR-5）。
 *
 * @param sessionId   会话 UUID
 * @param title       会话标题；首轮自动生成或人工重命名，无消息/未生成时为 {@code null}
 *                    （fastjson2 默认省略 null 键，前端兜底显示「新会话」）
 * @param createdAt   创建时间
 * @param updatedAt   最近活跃时间（列表倒序依据）
 * @param previewRole 最近一条消息角色（user/assistant）；无消息的空会话为 {@code null}
 * @param previewText 最近一条消息内容按预览规则截断；无消息为 {@code null}
 */
public record SessionSummary(String sessionId, String title,
                             LocalDateTime createdAt, LocalDateTime updatedAt,
                             String previewRole, String previewText) {
}
