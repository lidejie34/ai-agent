package com.dj.ai.agentchat.dto.session;

import java.time.LocalDateTime;

/**
 * 历史消息视图（迭代4 FR-6）：某会话全量消息按时间升序，content 全文不截断。
 *
 * <p>迭代13（对话删除）扩容：带库 {@code id}（消息级删除/截断的稳定身份）；
 * {@code role} 新增 {@code context_reset} 取值——「清空上下文」标记行以伪消息形式
 * 下发，前端渲染为分隔线（FR-5）；content 恒为空串。tool_evidence 证据行仍被
 * service 层过滤，不下发。
 *
 * @param id        消息库主键（删除/截断锚点）
 * @param role      user / assistant / context_reset
 * @param content   消息全文（context_reset 恒为空串）
 * @param createdAt 消息创建时间
 */
public record SessionMessageView(Long id, String role, String content, LocalDateTime createdAt) {
}
