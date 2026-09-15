package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.rag.dim.DimAdminService;
import com.dj.ai.agentchat.rag.dim.DimProject;
import com.dj.ai.agentchat.rag.dim.DimTagView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天侧维度选项端点（迭代10 追加）：{@code GET /api/kb/dimensions} 返回已维护项目名 +
 * 现有标签名，供聊天页项目/标签选择器下拉。
 *
 * <p>无 admin token（聊天页公开）；只暴露名称字符串，不暴露文档数等管理视角数据。
 * RAG 开关关闭时 service bean 不装配——返回空数组（200），前端静默隐藏选择器，
 * 不影响对话主流程。
 */
@RestController
@RequestMapping("/api/kb")
public class KbDimensionController {

    /** 维度选项视图：projects/tags 均为名称列表。 */
    public record KbDimensionOptions(List<String> projects, List<String> tags) {
    }

    private static final KbDimensionOptions EMPTY = new KbDimensionOptions(List.of(), List.of());

    private final ObjectProvider<DimAdminService> serviceProvider;

    public KbDimensionController(ObjectProvider<DimAdminService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @GetMapping("/dimensions")
    public KbDimensionOptions dimensions() {
        DimAdminService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return EMPTY;
        }
        return new KbDimensionOptions(
                service.listProjects().stream().map(DimProject::name).toList(),
                service.listTags().stream().map(DimTagView::name).toList());
    }
}
