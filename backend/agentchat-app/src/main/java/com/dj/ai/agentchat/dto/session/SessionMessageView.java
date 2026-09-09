package com.dj.ai.agentchat.dto.session;

import java.time.LocalDateTime;

/**
 * 历史消息视图（迭代4 FR-6）：某会话全量消息按时间升序，content 全文不截断。
 *
 * @param role      user / assistant
 * @param content   消息全文
 * @param createdAt 消息创建时间
 */
public record SessionMessageView(String role, String content, LocalDateTime createdAt) {
}
