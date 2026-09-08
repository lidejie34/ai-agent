package com.dj.ai.agentchat.orchestration.frame;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 编排过程帧（迭代5，T4）：经 {@code OrchEventBridge} 由 ChatController 转发为
 * SSE {@code event:plan} / {@code event:task}。null 字段 JSON 省键（fastjson2 默认、
 * Jackson {@link JsonInclude.Include#NON_NULL} 双保险）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface OrchFrame permits PlanFrame, TaskFrame {
}
