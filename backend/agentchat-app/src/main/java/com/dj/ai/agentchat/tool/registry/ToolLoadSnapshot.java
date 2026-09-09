package com.dj.ai.agentchat.tool.registry;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具装载快照（插入迭代 G）：
 * <ul>
 *   <li>{@code empty()}：启动初始态——尚未装载过，下次取用时尝试装载；</li>
 *   <li>{@code failed()}：装载失败——<b>不缓存失败</b>（loaded=false），下次请求重试，
 *       DB 恢复后自愈；</li>
 *   <li>{@code loaded(list)}：装载成功（含空集——DB 中无启用工具，空集是确定结果，零 DB 复用）。</li>
 * </ul>
 */
public record ToolLoadSnapshot(boolean ok, boolean loaded, List<ToolCallback> callbacks) {

    private static final ToolLoadSnapshot EMPTY = new ToolLoadSnapshot(false, false, List.of());

    public static ToolLoadSnapshot empty() {
        return EMPTY;
    }

    public static ToolLoadSnapshot failed() {
        return new ToolLoadSnapshot(false, false, List.of());
    }

    public static ToolLoadSnapshot loaded(List<ToolCallback> callbacks) {
        return new ToolLoadSnapshot(true, true, List.copyOf(callbacks));
    }
}
