package com.dj.ai.agentchat.tool.handler;

/**
 * 工具执行结果（插入迭代 G）。处理器实现内部全捕获，永不抛 RuntimeException：
 * 成功为 Markdown 文本；失败为错误码 + 人类可读原因（DbToolCallback 统一包装为
 * 结构化 JSON 回传模型，模型可据指南自我纠正后重调）。
 *
 * @param ok           是否成功
 * @param status       审计/帧状态：SUCCESS / FAILED / TIMEOUT
 * @param text         成功为分析 Markdown；失败可为局部输出（追加到错误 JSON detail，可空）
 * @param errorCode    失败错误码（INVALID_ARGS / SCRIPT_EXIT_NONZERO / TOOL_TIMEOUT / ...）；成功为 null
 * @param errorMessage 失败原因（脱敏前原文，无堆栈）；成功为 null
 */
public record ToolExecutionResult(boolean ok,
                                  String status,
                                  String text,
                                  String errorCode,
                                  String errorMessage) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public static ToolExecutionResult success(String text) {
        return new ToolExecutionResult(true, STATUS_SUCCESS, text, null, null);
    }

    public static ToolExecutionResult failed(String errorCode, String errorMessage) {
        return new ToolExecutionResult(false, STATUS_FAILED, null, errorCode, errorMessage);
    }

    public static ToolExecutionResult failed(String errorCode, String errorMessage, String partialText) {
        return new ToolExecutionResult(false, STATUS_FAILED, partialText, errorCode, errorMessage);
    }

    public static ToolExecutionResult timeout(String errorMessage) {
        return new ToolExecutionResult(false, STATUS_TIMEOUT, null, "TOOL_TIMEOUT", errorMessage);
    }
}
