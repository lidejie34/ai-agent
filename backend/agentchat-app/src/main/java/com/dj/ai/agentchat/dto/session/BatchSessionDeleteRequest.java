package com.dj.ai.agentchat.dto.session;

import java.util.List;

/**
 * 批量删除会话请求体（迭代13 FR-3）。
 *
 * @param ids 会话 UUID 列表；空/缺席 → 400；任一非法 UUID → 400（整请求拒绝）
 */
public record BatchSessionDeleteRequest(List<String> ids) {
}
