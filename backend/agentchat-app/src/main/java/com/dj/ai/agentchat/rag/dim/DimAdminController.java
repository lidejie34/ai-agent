package com.dj.ai.agentchat.rag.dim;

import com.dj.ai.agentchat.rag.admin.KbAdminException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 维度维护管理端接口（迭代10 追加）：{@code /api/admin/dim/projects} 受管项目 CRUD +
 * {@code /api/admin/dim/tags} 标签派生视图（改名/删除联动文档）。
 *
 * <p><b>常驻装配</b>：与 KbAdminController 同模式——控制器无条件在组件扫描内，
 * {@code app.rag.enabled=false} 时先被 {@code AdminAuthInterceptor} 路径闸门
 * （dim → Gate.RAG）挡为 503 KB_DISABLED；service 缺席仅作防御性 503。
 * 鉴权（X-Admin-Token）全部在拦截器，本类不重复鉴权。
 */
@RestController
@RequestMapping("/api/admin/dim")
public class DimAdminController {

    private final ObjectProvider<DimAdminService> serviceProvider;

    public DimAdminController(ObjectProvider<DimAdminService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /** 项目新建/更新请求体：name 必填（白名单校验在 service），remark 可空。 */
    public record ProjectUpsert(String name, String remark) {
    }

    /** 标签改名请求体：from/to 必填。 */
    public record TagRename(String from, String to) {
    }

    /** 标签改名/删除响应：受影响文档数。 */
    public record TagOpResult(int affectedDocs) {
    }

    @GetMapping("/projects")
    public List<DimProject> listProjects() {
        return service().listProjects();
    }

    @PostMapping("/projects")
    public ResponseEntity<DimProject> createProject(@RequestBody ProjectUpsert body) {
        requireBody(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service().createProject(body.name(), body.remark()));
    }

    @PatchMapping("/projects/{id}")
    public DimProject updateProject(@PathVariable Long id, @RequestBody ProjectUpsert body) {
        requireBody(body);
        return service().updateProject(id, body.name(), body.remark());
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        service().deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tags")
    public List<DimTagView> listTags() {
        return service().listTags();
    }

    @PatchMapping("/tags")
    public TagOpResult renameTag(@RequestBody TagRename body) {
        if (body == null) {
            throw new KbAdminException(KbAdminException.KB_INVALID_TAGS,
                    "请求体为空：需含 from 与 to");
        }
        return new TagOpResult(service().renameTag(body.from(), body.to()));
    }

    @DeleteMapping("/tags/{name}")
    public TagOpResult deleteTag(@PathVariable String name) {
        return new TagOpResult(service().deleteTag(name));
    }

    private static void requireBody(ProjectUpsert body) {
        if (body == null) {
            throw new KbAdminException(KbAdminException.KB_INVALID_PROJECT,
                    "请求体为空：需含 name（remark 可空）");
        }
    }

    private DimAdminService service() {
        DimAdminService service = serviceProvider.getIfAvailable();
        if (service == null) {
            // 正常不可达：开关关闭时拦截器已先返 503 KB_DISABLED
            throw new KbAdminException(KbAdminException.KB_DISABLED, "知识库功能未启用");
        }
        return service;
    }
}
