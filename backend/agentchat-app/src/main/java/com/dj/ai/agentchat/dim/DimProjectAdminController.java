package com.dj.ai.agentchat.dim;

import com.dj.ai.agentchat.rag.admin.KbAdminException;
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
 * 受管项目管理端接口（迭代10 迁移；自 rag.dim.DimAdminController 拆分）：
 * {@code /api/admin/dim/projects} CRUD。
 *
 * <p><b>无条件装配</b>：dim_project 在 MySQL 主库，不随 RAG 开关——AdminGateResolver
 * 对 dim/projects 段放行 TOKEN_ONLY（仅 X-Admin-Token 鉴权，在拦截器，本类不重复）。
 * 标签端点（rag 侧派生视图）见 {@code DimTagAdminController}。
 */
@RestController
@RequestMapping("/api/admin/dim/projects")
public class DimProjectAdminController {

    /** 项目新建/更新请求体：name 必填（白名单校验在 service），remark 可空。 */
    public record ProjectUpsert(String name, String remark) {
    }

    private final DimProjectService service;

    public DimProjectAdminController(DimProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<DimProject> listProjects() {
        return service.listProjects();
    }

    @PostMapping
    public ResponseEntity<DimProject> createProject(@RequestBody ProjectUpsert body) {
        requireBody(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createProject(body.name(), body.remark()));
    }

    @PatchMapping("/{id}")
    public DimProject updateProject(@PathVariable Long id, @RequestBody ProjectUpsert body) {
        requireBody(body);
        return service.updateProject(id, body.name(), body.remark());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        service.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    private static void requireBody(ProjectUpsert body) {
        if (body == null) {
            throw new KbAdminException(KbAdminException.KB_INVALID_PROJECT,
                    "请求体为空：需含 name（remark 可空）");
        }
    }
}
