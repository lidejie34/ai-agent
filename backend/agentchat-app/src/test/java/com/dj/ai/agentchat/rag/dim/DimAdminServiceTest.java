package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 追加：DimAdminService 单测——项目创建（重名 409/非法 400）、
 * 改名联动文档（单事务语义在集成层）、引用中禁删（409 带引用数）、
 * 标签改名/删除（目标名校验、from==to 无操作）。
 */
class DimAdminServiceTest {

    private DimRepository repository;
    private DimAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(DimRepository.class);
        RagSchemaInitializer schemaInitializer = mock(RagSchemaInitializer.class);
        service = new DimAdminService(repository, new RagProperties(), schemaInitializer);
    }

    private static DimProject project(long id, String name, long docCount) {
        return new DimProject(id, name, null, docCount,
                LocalDateTime.of(2026, 9, 15, 10, 0), LocalDateTime.of(2026, 9, 15, 10, 0));
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
    void updateProject_rename_propagatesToDocuments() {
        when(repository.findProjectById(5L))
                .thenReturn(Optional.of(project(5L, "订单域", 2)))
                .thenReturn(Optional.of(project(5L, "交易域", 2)));

        service.updateProject(5L, "交易域", null);

        verify(repository).updateProject(5L, "交易域", null);
        verify(repository).renameProjectDocs("订单域", "交易域");
    }

    @Test
    void updateProject_sameName_skipsDocPropagation() {
        when(repository.findProjectById(5L)).thenReturn(Optional.of(project(5L, "订单域", 2)));

        service.updateProject(5L, "订单域", "新备注");

        verify(repository).updateProject(5L, "订单域", "新备注");
        verify(repository, never()).renameProjectDocs(anyString(), anyString());
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
        when(repository.countDocsByProject("订单域")).thenReturn(4L);

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
        when(repository.countDocsByProject("订单域")).thenReturn(0L);

        service.deleteProject(5L);

        verify(repository).deleteProject(5L);
    }

    @Test
    void renameTag_happy_delegates() {
        when(repository.renameTag("退货", "换货")).thenReturn(2);

        int affected = service.renameTag(" 退货 ", "换货");

        assertThat(affected).isEqualTo(2);
        verify(repository).renameTag("退货", "换货");
    }

    @Test
    void renameTag_sameName_noOp() {
        int affected = service.renameTag("售后", " 售后 ");

        assertThat(affected).isZero();
        verify(repository, never()).renameTag(anyString(), anyString());
    }

    @Test
    void renameTag_invalidTarget_throws400() {
        assertThatThrownBy(() -> service.renameTag("退货", "含,逗号"))
                .isInstanceOf(KbAdminException.class)
                .extracting(e -> ((KbAdminException) e).getCode())
                .isEqualTo(KbAdminException.KB_INVALID_TAGS);
        verify(repository, never()).renameTag(anyString(), anyString());
    }

    @Test
    void deleteTag_happy_delegates() {
        when(repository.deleteTag("退货")).thenReturn(1);

        assertThat(service.deleteTag("退货")).isEqualTo(1);
        verify(repository).deleteTag("退货");
    }
}
