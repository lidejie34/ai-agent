package com.dj.ai.agentchat.dim;

import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.dim.DimRagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 迁移：DimProjectService 单测——项目创建（重名 409/非法 400）、docCount 跨库合并
 * （rag 仓储在场/缺席）、改名顺序（先 PG 文档联动后 MySQL 行）与失败补偿、
 * 改名重名预检 409（不动笔）、引用中禁删（409 带引用数；RAG 缺席按 0 引用）。
 */
class DimProjectServiceTest {

    private DimProjectRepository repository;
    private DimRagRepository ragRepository;
    private ObjectProvider<DimRagRepository> ragProvider;
    private DimProjectService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DimProjectRepository.class);
        ragRepository = mock(DimRagRepository.class);
        ragProvider = mock(ObjectProvider.class);
        when(ragProvider.getIfAvailable()).thenReturn(ragRepository);
        DimProjectSchemaInitializer schemaInitializer = mock(DimProjectSchemaInitializer.class);
        service = new DimProjectService(repository, schemaInitializer, ragProvider);
    }

    private static DimProject project(long id, String name, long docCount) {
        return new DimProject(id, name, null, docCount,
                LocalDateTime.of(2026, 9, 15, 10, 0), LocalDateTime.of(2026, 9, 15, 10, 0));
    }

    @Test
    void listProjects_mergesDocCountFromRagSide() {
        when(repository.listProjects()).thenReturn(List.of(
                project(1L, "订单域", 0), project(2L, "物流域", 0)));
        when(ragRepository.countDocsGroupByProject()).thenReturn(Map.of("订单域", 3L));

        List<DimProject> views = service.listProjects();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).docCount()).isEqualTo(3L);
        assertThat(views.get(1).docCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProjects_ragAbsent_docCountStaysZero() {
        ObjectProvider<DimRagRepository> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        DimProjectSchemaInitializer schemaInitializer = mock(DimProjectSchemaInitializer.class);
        DimProjectService ragOffService =
                new DimProjectService(repository, schemaInitializer, emptyProvider);
        when(repository.listProjects()).thenReturn(List.of(project(1L, "订单域", 0)));

        List<DimProject> views = ragOffService.listProjects();

        assertThat(views.get(0).docCount()).isZero();
        verify(ragRepository, never()).countDocsGroupByProject();
    }

    @Test
    void createProject_happy_normalizesAndReturnsView() {
        when(repository.insertProject("订单域", "订单制度")).thenReturn(3L);
        when(repository.findProjectById(3L)).thenReturn(Optional.of(project(3L, "订单域", 0)));

        DimProject created = service.createProject(" 订单域 ", "订单制度");

        verify(repository).insertProject("订单域", "订单制度");
        assertThat(created.name()).isEqualTo("订单域");
    }

    @Test
    void createProject_duplicate_throws409Exists() {
        when(repository.insertProject(anyString(), isNull()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

        assertThatThrownBy(() -> service.createProject("订单域", null))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_PROJECT_EXISTS);
    }

    @Test
    void createProject_invalidName_throws400() {
        assertThatThrownBy(() -> service.createProject("含,逗号", null))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_PROJECT);
        assertThatThrownBy(() -> service.createProject("  ", null))
                .isInstanceOf(KbAdminException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void updateProject_rename_propagatesDocsBeforeMysqlRow() {
        when(repository.findProjectById(5L))
                .thenReturn(Optional.of(project(5L, "订单域", 2)))
                .thenReturn(Optional.of(project(5L, "交易域", 0)));
        when(repository.projectExists("交易域")).thenReturn(false);
        when(ragRepository.countDocsByProject("交易域")).thenReturn(2L);

        DimProject updated = service.updateProject(5L, "交易域", null);

        // 顺序钉死：先 PG 文档联动（PG 挂则什么都没变），后 MySQL 项目行
        var order = inOrder(ragRepository, repository);
        order.verify(ragRepository).renameProjectDocs("订单域", "交易域");
        order.verify(repository).updateProject(5L, "交易域", null);
        // 回读视图补跨库引用计数（findProjectById 不带计数）
        assertThat(updated.docCount()).isEqualTo(2L);
    }

    @Test
    void updateProject_renameToExisting_prechecks409WithoutAnyWrite() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 2)));
        when(repository.projectExists("交易域")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProject(5L, "交易域", null))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_PROJECT_EXISTS);
        // 预检拦截：两侧都不动笔
        verify(ragRepository, never()).renameProjectDocs(anyString(), anyString());
        verify(repository, never()).updateProject(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void updateProject_mysqlFailureAfterPropagation_compensatesDocsBack() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 2)));
        when(repository.projectExists("交易域")).thenReturn(false);
        when(repository.updateProject(5L, "交易域", null))
                .thenThrow(new RuntimeException("mysql down"));

        assertThatThrownBy(() -> service.updateProject(5L, "交易域", null))
                .hasMessageContaining("mysql down");

        // 补偿：文档归属回改旧名
        verify(ragRepository).renameProjectDocs("订单域", "交易域");
        verify(ragRepository).renameProjectDocs("交易域", "订单域");
    }

    @Test
    void updateProject_sameName_skipsDocPropagation() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 2)));

        service.updateProject(5L, "订单域", "新备注");

        verify(repository).updateProject(5L, "订单域", "新备注");
        verify(ragRepository, never()).renameProjectDocs(anyString(), anyString());
    }

    @Test
    void updateProject_notFound_throws404() {
        when(repository.findProjectById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProject(99L, "x", null))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_PROJECT_NOT_FOUND);
    }

    @Test
    void deleteProject_inUse_throws409WithRefCount() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 4)));
        when(ragRepository.countDocsByProject("订单域")).thenReturn(4L);

        assertThatThrownBy(() -> service.deleteProject(5L))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_PROJECT_IN_USE);
        assertThatThrownBy(() -> service.deleteProject(5L))
                .hasMessageContaining("4 篇文档");
        verify(repository, never()).deleteProject(anyLong());
    }

    @Test
    void deleteProject_unreferenced_deletes() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 0)));
        when(ragRepository.countDocsByProject("订单域")).thenReturn(0L);

        service.deleteProject(5L);

        verify(repository).deleteProject(5L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteProject_ragAbsent_zeroRefsDeletes() {
        ObjectProvider<DimRagRepository> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        DimProjectSchemaInitializer schemaInitializer = mock(DimProjectSchemaInitializer.class);
        DimProjectService ragOffService =
                new DimProjectService(repository, schemaInitializer, emptyProvider);
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 0)));

        ragOffService.deleteProject(5L);

        verify(repository).deleteProject(5L);
    }

    @Test
    void projectExists_delegates() {
        when(repository.projectExists("订单域")).thenReturn(true);

        assertThat(service.projectExists("订单域")).isTrue();
    }
}
