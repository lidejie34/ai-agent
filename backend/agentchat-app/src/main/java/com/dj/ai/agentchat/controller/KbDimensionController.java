package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dim.DimProject;
import com.dj.ai.agentchat.dim.DimProjectService;
import com.dj.ai.agentchat.rag.dim.DimTagService;
import com.dj.ai.agentchat.rag.dim.DimTagView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天侧维度选项端点（迭代10）：{@code GET /api/kb/dimensions} 返回已维护项目名 +
 * 现有标签名，供聊天页项目/标签选择器下拉。
 *
 * <p>无 admin token（聊天页公开）；只暴露名称字符串，不暴露文档数等管理视角数据。
 * 项目名来自 MySQL 主库 dim_project（DimProjectService 无条件装配）；标签是
 * rag_document 派生视图——RAG 开关关闭时标签 service 缺席，整体返回空数组（200），
 * 前端静默隐藏选择器（对话无检索，选择维度无意义），不影响对话主流程。
 */
@RestController
@RequestMapping("/api/kb")
public class KbDimensionController {

    /** 维度选项视图：projects/tags 均为名称列表。 */
    public record KbDimensionOptions(List<String> projects, List<String> tags) {
    }

    private static final KbDimensionOptions EMPTY = new KbDimensionOptions(List.of(), List.of());

    private final DimProjectService projectService;
    private final ObjectProvider<DimTagService> tagServiceProvider;

    public KbDimensionController(DimProjectService projectService,
                                 ObjectProvider<DimTagService> tagServiceProvider) {
        this.projectService = projectService;
        this.tagServiceProvider = tagServiceProvider;
    }

    @GetMapping("/dimensions")
    public KbDimensionOptions dimensions() {
        DimTagService tagService = tagServiceProvider.getIfAvailable();
        if (tagService == null) {
            return EMPTY;
        }
        return new KbDimensionOptions(
                projectService.listProjects().stream().map(DimProject::name).toList(),
                tagService.listTags().stream().map(DimTagView::name).toList());
    }
}
