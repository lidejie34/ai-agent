package com.dj.ai.agentchat.dto;

import java.util.List;

/**
 * 知识库检索维度过滤（迭代10；迭代11 项目升级为多选）：对话请求级 kbProjects/kbTags
 * 经控制层校验规整后的载体，随 ChatService → RagAdvisor（advisor param）/
 * OrchInput → ExecutorClient 传递。
 *
 * <p>两维语义：projects 任一命中（OR，项目等值集合）；tags 任一命中（OR）；
 * 两维之间 AND。{@link #present()} 为 false 时不注入 advisor param——
 * 请求形态与迭代9 逐字节一致。
 *
 * <p>三下拉「都不加载」第三态：{@link #disabled()} 为 true = 显式 kbProjects:[]
 * （本轮完全不加载知识库）——挂载 {@code RagAdvisor.PARAM_KB_DISABLED} 标记，
 * RagAdvisor 据此直接放行（不 embedding、不检索、不注入）。
 */
public record KbFilter(List<String> projects, List<String> tags, boolean disabled) {

    public KbFilter {
        projects = projects == null ? List.of() : List.copyOf(projects);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** 两参兼容构造（迭代10/11 调用点零改动）：disabled=false。 */
    public KbFilter(List<String> projects, List<String> tags) {
        this(projects, tags, false);
    }

    /** 显式「都不加载」（kbProjects:[]）：本轮完全跳过知识库检索。 */
    public static KbFilter none() {
        return new KbFilter(List.of(), List.of(), true);
    }

    /** 是否携带任一维度（false = 不过滤，全库检索）。 */
    public boolean present() {
        return !projects.isEmpty() || !tags.isEmpty();
    }
}
