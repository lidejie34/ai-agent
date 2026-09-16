package com.dj.ai.agentchat.dto.session;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 会话级范围配置 PUT 请求体（迭代12）：四字段全量覆盖语义（非部分更新）——
 * {@code null}=恢复默认全部；空数组=显式全不选；非空=子集。
 * 前端选择器任何变化都应以最新完整状态 PUT。
 */
public record SessionScopeUpdate(@Nullable List<String> kbProjects,
                                 @Nullable List<String> kbTags,
                                 @Nullable List<String> toolNames,
                                 @Nullable List<String> mcpServers) {
}
