package com.dj.ai.agentchat.dto.session;

import java.util.List;

/**
 * 批量删除会话结果（迭代13 FR-3）：批量语义，逐 id 汇报，不以 404 打断整批。
 *
 * @param deleted  实际删除的会话 id
 * @param notFound 不存在或已被删除的会话 id（前端提示并刷新列表）
 */
public record BatchSessionDeleteResult(List<String> deleted, List<String> notFound) {
}
