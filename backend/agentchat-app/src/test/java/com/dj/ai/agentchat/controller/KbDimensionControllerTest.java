package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dim.DimProject;
import com.dj.ai.agentchat.dim.DimProjectService;
import com.dj.ai.agentchat.rag.dim.DimTagService;
import com.dj.ai.agentchat.rag.dim.DimTagView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 迁移：KbDimensionController 单测——标签 service 在场（RAG 开）时项目名取自
 * MySQL 侧 DimProjectService + 标签名取自 rag 侧 DimTagService；标签 service 缺席
 * （RAG 关）整体返回空数组而非异常（聊天页静默隐藏选择器）。
 */
class KbDimensionControllerTest {

    @Test
    void dimensions_ragOn_mapsNamesFromBothSides() {
        DimProjectService projectService = mock(DimProjectService.class);
        when(projectService.listProjects()).thenReturn(List.of(
                new DimProject(1L, "订单域", "备注", 3, LocalDateTime.now(), LocalDateTime.now()),
                new DimProject(2L, "物流域", null, 0, LocalDateTime.now(), LocalDateTime.now())));
        DimTagService tagService = mock(DimTagService.class);
        when(tagService.listTags()).thenReturn(List.of(
                new DimTagView("售后", 5), new DimTagView("承运", 2)));
        @SuppressWarnings("unchecked")
        ObjectProvider<DimTagService> tagProvider = mock(ObjectProvider.class);
        when(tagProvider.getIfAvailable()).thenReturn(tagService);

        KbDimensionController.KbDimensionOptions options =
                new KbDimensionController(projectService, tagProvider).dimensions();

        assertThat(options.projects()).containsExactly("订单域", "物流域");
        assertThat(options.tags()).containsExactly("售后", "承运");
    }

    @Test
    void dimensions_ragOff_returnsEmptyListsWithoutTouchingProjectService() {
        DimProjectService projectService = mock(DimProjectService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DimTagService> tagProvider = mock(ObjectProvider.class);
        when(tagProvider.getIfAvailable()).thenReturn(null);

        KbDimensionController.KbDimensionOptions options =
                new KbDimensionController(projectService, tagProvider).dimensions();

        assertThat(options.projects()).isEmpty();
        assertThat(options.tags()).isEmpty();
        // RAG 关闭时选择器整体隐藏，不读项目表（对话无检索，维度无意义）
        verify(projectService, never()).listProjects();
    }
}
