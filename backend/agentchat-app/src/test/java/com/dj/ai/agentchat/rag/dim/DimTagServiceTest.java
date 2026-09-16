package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代10 迁移：DimTagService 单测（原 DimAdminServiceTest 的标签半边）——
 * 标签改名/删除（目标名白名单校验、from==to 无操作、联动文档数透传）。
 */
class DimTagServiceTest {

    private DimRagRepository repository;
    private DimTagService service;

    @BeforeEach
    void setUp() {
        repository = mock(DimRagRepository.class);
        RagSchemaInitializer schemaInitializer = mock(RagSchemaInitializer.class);
        service = new DimTagService(repository, new RagProperties(), schemaInitializer);
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
    void renameTag_blankName_throws400() {
        assertThatThrownBy(() -> service.renameTag("  ", "换货"))
                .isInstanceOf(KbAdminException.class)
                .hasMessageContaining("不能为空");
        verify(repository, never()).renameTag(anyString(), anyString());
    }

    @Test
    void deleteTag_happy_delegates() {
        when(repository.deleteTag("退货")).thenReturn(1);

        assertThat(service.deleteTag("退货")).isEqualTo(1);
        verify(repository).deleteTag("退货");
    }
}
