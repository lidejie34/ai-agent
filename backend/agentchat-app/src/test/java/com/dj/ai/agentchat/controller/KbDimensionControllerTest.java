package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.rag.dim.DimAdminService;
import com.dj.ai.agentchat.rag.dim.DimProject;
import com.dj.ai.agentchat.rag.dim.DimTagView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代10 追加：KbDimensionController 单测——service 在场时映射名称列表；
 * 缺席（RAG 开关关闭）返回空数组而非异常（聊天页静默隐藏选择器）。
 */
class KbDimensionControllerTest {

    @Test
    void dimensions_servicePresent_mapsNamesOnly() {
        DimAdminService service = mock(DimAdminService.class);
        when(service.listProjects()).thenReturn(List.of(
                new DimProject(1L, "订单域", "备注", 3, LocalDateTime.now(), LocalDateTime.now()),
                new DimProject(2L, "物流域", null, 0, LocalDateTime.now(), LocalDateTime.now())));
        when(service.listTags()).thenReturn(List.of(
                new DimTagView("售后", 5), new DimTagView("承运", 2)));
        @SuppressWarnings("unchecked")
        ObjectProvider<DimAdminService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        KbDimensionController.KbDimensionOptions options =
                new KbDimensionController(provider).dimensions();

        assertThat(options.projects()).containsExactly("订单域", "物流域");
        assertThat(options.tags()).containsExactly("售后", "承运");
    }

    @Test
    void dimensions_serviceAbsent_returnsEmptyLists() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DimAdminService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        KbDimensionController.KbDimensionOptions options =
                new KbDimensionController(provider).dimensions();

        assertThat(options.projects()).isEmpty();
        assertThat(options.tags()).isEmpty();
    }
}
