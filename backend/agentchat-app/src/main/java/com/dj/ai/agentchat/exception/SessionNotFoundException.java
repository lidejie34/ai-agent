package com.dj.ai.agentchat.exception;

/**
 * 会话不存在或已被删除（迭代4 FR-6/7/8）：会话 REST 接口路径 UUID 合法但库中无对应行时抛出，
 * 映射 404 {@code SESSION_NOT_FOUND}。区别于聊天续接路径的「未知 UUID 自愈补会话行」——
 * 会话管理接口不做自愈，明确 404 以便前端从侧边栏移除该项（FR-17.2）。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String message) {
        super(message);
    }
}
