package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.support.KbMetaValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_INVALID_TAGS;

/**
 * 标签维度编排（迭代10；原 DimAdminService 的标签半边——dim_project 迁 MySQL 后
 * 标签仍是 rag_document.tags 的派生视图，留在 rag 侧）：列表 + 改名/删除联动 JSONB 数组。
 *
 * <p>标签改名目标走 {@link KbMetaValidator} 白名单校验（与上传写侧同口径，禁逗号约束不变）。
 */
@Slf4j
public class DimTagService {

    private final DimRagRepository repository;
    private final RagProperties properties;
    private final RagSchemaInitializer schemaInitializer;

    public DimTagService(DimRagRepository repository,
                         RagProperties properties,
                         RagSchemaInitializer schemaInitializer) {
        this.repository = repository;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
    }

    public List<DimTagView> listTags() {
        schemaInitializer.ensureSchema();
        return repository.listTags();
    }

    /**
     * 标签改名联动：目标名走白名单校验；from 无引用时视为无操作（返回 0）。
     * from == to 直接无操作。
     */
    public int renameTag(String rawFrom, String rawTo) {
        schemaInitializer.ensureSchema();
        String from = normalizeTagName(rawFrom);
        String to = normalizeTagName(rawTo);
        if (from.equals(to)) {
            return 0;
        }
        int docs = repository.renameTag(from, to);
        log.info("维度标签改名: {} → {}（联动 {} 篇文档）", from, to, docs);
        return docs;
    }

    /** 标签删除联动（从所有文档 tags 数组移除）；返回受影响文档数。 */
    public int deleteTag(String rawName) {
        schemaInitializer.ensureSchema();
        String name = normalizeTagName(rawName);
        int docs = repository.deleteTag(name);
        log.info("维度标签删除: {}（联动 {} 篇文档）", name, docs);
        return docs;
    }

    private String normalizeTagName(String raw) {
        try {
            List<String> tags = KbMetaValidator.normalizeTags(
                    raw == null ? List.of() : List.of(raw), 1,
                    properties.getMeta().getMaxTagLength());
            if (tags.isEmpty()) {
                throw new KbAdminException(KB_INVALID_TAGS, "标签名不能为空");
            }
            return tags.get(0);
        } catch (KbMetaValidator.KbMetaInvalidException e) {
            throw new KbAdminException(KB_INVALID_TAGS, e.getMessage());
        }
    }
}
