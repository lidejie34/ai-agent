package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.support.KbMetaValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_INVALID_PROJECT;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_INVALID_TAGS;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_EXISTS;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_IN_USE;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_NOT_FOUND;

/**
 * 维度维护编排（迭代10 追加）：项目受管 CRUD（改名联动文档、引用中禁删）+
 * 标签派生视图（改名/删除联动 JSONB 数组）。
 *
 * <p>项目名校验复用 {@link KbMetaValidator} 白名单（与 rag_document.project 写侧同口径）；
 * 标签改名目标同样走白名单校验（写侧禁逗号约束不变）。
 */
@Slf4j
public class DimAdminService {

    private final DimRepository repository;
    private final RagProperties properties;
    private final RagSchemaInitializer schemaInitializer;

    public DimAdminService(DimRepository repository,
                           RagProperties properties,
                           RagSchemaInitializer schemaInitializer) {
        this.repository = repository;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
    }

    public List<DimProject> listProjects() {
        schemaInitializer.ensureSchema();
        return repository.listProjects();
    }

    public List<DimTagView> listTags() {
        schemaInitializer.ensureSchema();
        return repository.listTags();
    }

    /** 新建项目：重名 409 KB_PROJECT_EXISTS；非法名 400 KB_INVALID_PROJECT。 */
    public DimProject createProject(String rawName, String remark) {
        schemaInitializer.ensureSchema();
        String name = normalizeProjectName(rawName);
        String safeRemark = normalizeRemark(remark);
        try {
            long id = repository.insertProject(name, safeRemark);
            log.info("维度项目创建: {}", name);
            return repository.findProjectById(id)
                    .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                            "项目创建后回读失败: " + name));
        } catch (DuplicateKeyException e) {
            throw new KbAdminException(KB_PROJECT_EXISTS, "项目已存在：" + name);
        }
    }

    /**
     * 更新项目（改名 + 备注）：改名与文档联动在单事务内（ragTransactionManager）；
     * 不存在 404；新名与其他项目冲突 409。
     */
    @Transactional(transactionManager = DimRepository.TX_MANAGER)
    public DimProject updateProject(long id, String rawName, String remark) {
        schemaInitializer.ensureSchema();
        DimProject existing = repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目不存在: id=" + id));
        String newName = normalizeProjectName(rawName);
        String safeRemark = normalizeRemark(remark);
        try {
            repository.updateProject(id, newName, safeRemark);
        } catch (DuplicateKeyException e) {
            throw new KbAdminException(KB_PROJECT_EXISTS, "项目已存在：" + newName);
        }
        if (!existing.name().equals(newName)) {
            int docs = repository.renameProjectDocs(existing.name(), newName);
            log.info("维度项目改名: {} → {}（联动 {} 篇文档）", existing.name(), newName, docs);
        }
        return repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目更新后回读失败: id=" + id));
    }

    /** 删除项目：有文档引用 409 KB_PROJECT_IN_USE（带引用数）；不存在 404。 */
    public void deleteProject(long id) {
        schemaInitializer.ensureSchema();
        DimProject existing = repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目不存在: id=" + id));
        long refs = repository.countDocsByProject(existing.name());
        if (refs > 0) {
            throw new KbAdminException(KB_PROJECT_IN_USE,
                    "项目「" + existing.name() + "」仍被 " + refs + " 篇文档引用，请先调整文档归属后再删除");
        }
        repository.deleteProject(id);
        log.info("维度项目删除: {}", existing.name());
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

    private String normalizeProjectName(String raw) {
        try {
            String name = KbMetaValidator.normalizeProject(raw,
                    properties.getMeta().getMaxProjectLength());
            if (name == null) {
                throw new KbAdminException(KB_INVALID_PROJECT, "项目名不能为空");
            }
            return name;
        } catch (KbMetaValidator.KbMetaInvalidException e) {
            throw new KbAdminException(KB_INVALID_PROJECT, e.getMessage());
        }
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

    /** 备注：trim、空白归 null、截断 255（DDL VARCHAR(255)）。 */
    private static String normalizeRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String v = remark.trim();
        return v.length() > 255 ? v.substring(0, 255) : v;
    }
}
