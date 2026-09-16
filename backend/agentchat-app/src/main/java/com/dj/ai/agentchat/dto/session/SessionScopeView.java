package com.dj.ai.agentchat.dto.session;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 会话级范围配置视图（迭代12）：GET /api/sessions/{sid}/scope 响应。
 * 四字段统一三态：{@code null}=默认全部；空数组=显式全不选；非空=子集。
 * 从未配置的会话（配置行缺席）返回四字段全 null 的视图（= 全默认）。
 */
public record SessionScopeView(@Nullable List<String> kbProjects,
                               @Nullable List<String> kbTags,
                               @Nullable List<String> toolNames,
                               @Nullable List<String> mcpServers) {

    /** 全默认视图（配置行缺席）。 */
    public static final SessionScopeView ALL_DEFAULT = new SessionScopeView(null, null, null, null);
}
