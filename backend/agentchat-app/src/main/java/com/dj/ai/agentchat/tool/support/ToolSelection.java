package com.dj.ai.agentchat.tool.support;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 对话级工具选择（迭代12 FR-1/D4）：请求级载体，随 ChatService → ToolSupport 传递。
 *
 * <p>两字段各自三态：{@code null}=默认全部（现状回归）；空列表=该类全不挂；
 * 非空=仅子集（未知名在挂载时自然忽略）。{@link #restrictive()} 为 false 时
 * 等价无选择——挂载路径与迭代11 逐字节一致。
 */
public record ToolSelection(@Nullable List<String> toolNames,
                            @Nullable List<String> mcpServers) {

    /** 是否携带任一限制（false = 全默认，不过滤）。 */
    public boolean restrictive() {
        return toolNames != null || mcpServers != null;
    }
}
