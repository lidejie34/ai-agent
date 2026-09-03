package com.dj.ai.agentchat.dto.session;

/**
 * 删除结果（迭代4 FR-7）：成功返回 {@code {"deleted":true}}。
 *
 * @param deleted 恒为 {@code true}（不存在的会话由服务端先判 404，不会静默 200）
 */
public record SessionDeleteResult(boolean deleted) {

    public static final SessionDeleteResult OK = new SessionDeleteResult(true);
}
