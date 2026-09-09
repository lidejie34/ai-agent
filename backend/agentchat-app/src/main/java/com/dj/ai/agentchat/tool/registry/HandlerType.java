package com.dj.ai.agentchat.tool.registry;

/**
 * 工具处理器类型（agent_tool.handler_type 列）。
 *
 * <p>{@link #BUILTIN}（进程内内置实现）与 {@link #SCRIPT}（白名单脚本）本期实现；
 * {@link #HTTP} / {@link #SCRIPT_DB} 为预留类型——枚举值可解析但无对应处理器，
 * 装载时跳过并 WARN（AC-16），管理端写入直接 400。
 */
public enum HandlerType {
    BUILTIN,
    SCRIPT,
    HTTP,
    SCRIPT_DB
}
