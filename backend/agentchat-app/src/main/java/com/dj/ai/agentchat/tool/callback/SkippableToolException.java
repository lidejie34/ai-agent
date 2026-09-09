package com.dj.ai.agentchat.tool.callback;

/**
 * 工具行装载时可跳过异常（插入迭代 G，内部异常，不进 HTTP 体系）：
 * 未知 handler_type、handler_config 非法 JSON、BUILTIN bean 键不存在、SCRIPT 脚本文件缺失等。
 *
 * <p>ToolRegistry 装载单行时捕获本异常 → 跳过该行并 WARN（AC-15/16），其余行照常；
 * 管理端校验路径不走本异常（直接抛 InvalidChatRequestException → 400）。
 */
public class SkippableToolException extends RuntimeException {

    public SkippableToolException(String message) {
        super(message);
    }
}
