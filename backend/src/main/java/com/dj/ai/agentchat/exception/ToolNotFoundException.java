package com.dj.ai.agentchat.exception;

/**
 * 工具不存在（插入迭代 G）：管理端 GET/PUT/PATCH/DELETE 工具 ID 在库中无对应行时抛出，
 * 映射 404 {@code TOOL_NOT_FOUND}。
 */
public class ToolNotFoundException extends RuntimeException {

    public ToolNotFoundException(String message) {
        super(message);
    }
}
