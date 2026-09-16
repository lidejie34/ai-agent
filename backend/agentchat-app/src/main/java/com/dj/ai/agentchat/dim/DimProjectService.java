package com.dj.ai.agentchat.dim;

import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.dim.DimRagRepository;
import com.dj.ai.agentchat.rag.support.KbMetaValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_INVALID_PROJECT;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_EXISTS;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_IN_USE;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_PROJECT_NOT_FOUND;

/**
 * 受管项目编排（迭代10 迁移；自 rag.dim.DimAdminService 拆分）：dim_project（MySQL 主库，
 * 无条件装配——RAG 关闭也能维护项目，供后续工具调度复用）+ 与 rag 侧文档的跨库联动。
 *
 * <p>跨库一致性策略（无 XA，按故障面排序）：
 * <ol>
 *   <li>改名前先预检重名（409），把 MySQL 更新失败率压到趋零；</li>
 *   <li>先改 PG 文档归属（PG 挂 → 什么都没变，干净重试），再改 MySQL 项目行；</li>
 *   <li>MySQL 更新万一失败 → best-effort 把文档归属改回旧名（补偿），异常原样上浮。</li>
 * </ol>
 * 引用计数（docCount）与引用检查经 {@link ObjectProvider} 懒取 rag 侧仓储——RAG 关闭时
 * 计数按 0、联动跳过（无文档可引用）。
 *
 * <p>项目名/标签白名单校验复用 rag 侧纯静态工具 {@link KbMetaValidator}（与
 * rag_document.project 写侧同口径）；错误码复用 {@link KbAdminException} 的 KB_PROJECT_*。
 */
@Slf4j
public class DimProjectService {

    /** 项目名长度上限：与 dim_project DDL VARCHAR(64) 一致（原取 RagProperties.meta 默认 64）。 */
    static final int MAX_PROJECT_LENGTH = 64;

    private final DimProjectRepository repository;
    private final DimProjectSchemaInitializer schemaInitializer;
    private final ObjectProvider<DimRagRepository> ragRepositoryProvider;

    public DimProjectService(DimProjectRepository repository,
                             DimProjectSchemaInitializer schemaInitializer,
                             ObjectProvider<DimRagRepository> ragRepositoryProvider) {
        this.repository = repository;
        this.schemaInitializer = schemaInitializer;
        this.ragRepositoryProvider = ragRepositoryProvider;
    }

    /** 项目列表（含引用文档数；RAG 关闭时 docCount 全 0）。 */
    public List<DimProject> listProjects() {
        schemaInitializer.ensureSchema();
        List<DimProject> projects = repository.listProjects();
        DimRagRepository rag = ragRepositoryProvider.getIfAvailable();
        if (rag == null || projects.isEmpty()) {
            return projects;
        }
        Map<String, Long> counts = rag.countDocsGroupByProject();
        return projects.stream()
                .map(p -> p.withDocCount(counts.getOrDefault(p.name(), 0L)))
                .toList();
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
     * 更新项目（改名 + 备注）：不存在 404；新名与其他项目冲突 409（先预检再动笔）。
     * 改名联动：先 PG 文档归属、后 MySQL 项目行，MySQL 失败时补偿回改文档归属。
     */
    public DimProject updateProject(long id, String rawName, String remark) {
        schemaInitializer.ensureSchema();
        DimProject existing = repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目不存在: id=" + id));
        String newName = normalizeProjectName(rawName);
        String safeRemark = normalizeRemark(remark);
        boolean renamed = !existing.name().equals(newName);
        DimRagRepository rag = ragRepositoryProvider.getIfAvailable();
        if (renamed) {
            // 预检重名：避免文档联动已发生后才撞唯一键（残留不一致的最大来源）
            if (repository.projectExists(newName)) {
                throw new KbAdminException(KB_PROJECT_EXISTS, "项目已存在：" + newName);
            }
            if (rag != null) {
                int docs = rag.renameProjectDocs(existing.name(), newName);
                log.info("维度项目改名联动: {} → {}（{} 篇文档）", existing.name(), newName, docs);
            }
        }
        try {
            repository.updateProject(id, newName, safeRemark);
        } catch (DuplicateKeyException e) {
            compensateRename(rag, renamed, existing.name(), newName);
            throw new KbAdminException(KB_PROJECT_EXISTS, "项目已存在：" + newName);
        } catch (RuntimeException e) {
            compensateRename(rag, renamed, existing.name(), newName);
            throw e;
        }
        if (renamed) {
            log.info("维度项目改名: {} → {}", existing.name(), newName);
        }
        DimProject updated = repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目更新后回读失败: id=" + id));
        return withDocCount(updated, rag);
    }

    /** 删除项目：有文档引用 409 KB_PROJECT_IN_USE（带引用数）；不存在 404。RAG 关闭视为 0 引用。 */
    public void deleteProject(long id) {
        schemaInitializer.ensureSchema();
        DimProject existing = repository.findProjectById(id)
                .orElseThrow(() -> new KbAdminException(KB_PROJECT_NOT_FOUND,
                        "项目不存在: id=" + id));
        DimRagRepository rag = ragRepositoryProvider.getIfAvailable();
        long refs = rag == null ? 0 : rag.countDocsByProject(existing.name());
        if (refs > 0) {
            throw new KbAdminException(KB_PROJECT_IN_USE,
                    "项目「" + existing.name() + "」仍被 " + refs + " 篇文档引用，请先调整文档归属后再删除");
        }
        repository.deleteProject(id);
        log.info("维度项目删除: {}", existing.name());
    }

    /** 项目是否已受管（知识库上传/编辑元数据的项目校验用）。 */
    public boolean projectExists(String name) {
        schemaInitializer.ensureSchema();
        return repository.projectExists(name);
    }

    /** 单项目视图补引用计数（rag 缺席按 0）——findProjectById 不带跨库计数，保持响应口径一致。 */
    private DimProject withDocCount(DimProject project, DimRagRepository rag) {
        return rag == null ? project
                : project.withDocCount(rag.countDocsByProject(project.name()));
    }

    /** MySQL 改名失败后的补偿：把已联动的文档归属改回旧名（best-effort，失败仅 error 日志）。 */
    private void compensateRename(DimRagRepository rag, boolean renamed,
                                  String oldName, String newName) {
        if (!renamed || rag == null) {
            return;
        }
        try {
            int docs = rag.renameProjectDocs(newName, oldName);
            log.warn("维度项目改名补偿: 文档归属已回改 {} → {}（{} 篇）", newName, oldName, docs);
        } catch (RuntimeException compensationError) {
            log.error("维度项目改名补偿失败（文档归属停留在新名 {}，请手工核对）: {}",
                    newName, compensationError.getMessage());
        }
    }

    private String normalizeProjectName(String raw) {
        try {
            String name = KbMetaValidator.normalizeProject(raw, MAX_PROJECT_LENGTH);
            if (name == null) {
                throw new KbAdminException(KB_INVALID_PROJECT, "项目名不能为空");
            }
            return name;
        } catch (KbMetaValidator.KbMetaInvalidException e) {
            throw new KbAdminException(KB_INVALID_PROJECT, e.getMessage());
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
