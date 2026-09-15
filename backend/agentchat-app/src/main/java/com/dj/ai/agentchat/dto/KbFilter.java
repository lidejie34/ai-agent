package com.dj.ai.agentchat.dto;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 知识库检索维度过滤（迭代10）：对话请求级 kbProject/kbTags 经控制层校验规整后的载体，
 * 随 ChatService → RagAdvisor（advisor param）/ OrchInput → ExecutorClient 传递。
 *
 * <p>两维语义：project 等值精确匹配；tags 任一命中（OR）。{@link #present()} 为 false
 * 时不注入 advisor param——请求形态与迭代9 逐字节一致。
 */
public record KbFilter(@Nullable String project, List<String> tags) {

    public KbFilter {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** 是否携带任一维度（false = 不过滤，全库检索）。 */
    public boolean present() {
        return (project != null && !project.isBlank()) || !tags.isEmpty();
    }
}
