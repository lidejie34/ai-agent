package com.dj.ai.agentchat.rag.admin;

import com.dj.ai.agentchat.rag.admin.dto.KbDocumentMetaPatch;
import com.dj.ai.agentchat.rag.admin.dto.KbDocumentView;
import com.dj.ai.agentchat.rag.admin.service.KbDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库管理端接口（迭代6）：{@code /api/admin/kb/documents} 上传（multipart 字段名 file）/
 * 列表/删除（级联）/重建索引；健康检查端点在 KbHealthController（#87）。
 *
 * <p>迭代10 维度元数据：上传表单新增可选 {@code project}/{@code tags}（逗号分隔）；
 * 列表新增可选 query {@code project}/{@code tag}；新增 {@code PATCH /documents/{id}}
 * 全量替换维度元数据（project 可 null=清除、tags 空=清空）。
 *
 * <p><b>常驻装配</b>：控制器本身无条件在组件扫描内——{@code app.rag.enabled=false} 时
 * {@link KbDocumentService} bean 不装配，请求先被 {@code AdminAuthInterceptor} 路径闸门
 * 挡为 503 {@code KB_DISABLED}（不会 404）；service 缺席仅作防御性 503。
 * 鉴权（X-Admin-Token）全部在拦截器，本类不重复鉴权。JSON 走 fastjson2 转换器。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/kb")
public class KbAdminController {

    private final ObjectProvider<KbDocumentService> serviceProvider;

    public KbAdminController(ObjectProvider<KbDocumentService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /**
     * 上传 Markdown/TXT（UTF-8）：同步切片+向量化，成功 201 返回 READY 视图。
     * 迭代10：表单可选 project（单值）/ tags（逗号分隔）打标，非法 400。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KbDocumentView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "project", required = false) String project,
            @RequestParam(value = "tags", required = false) String tags) {
        if (file == null || file.isEmpty()) {
            throw new KbAdminException(KbAdminException.KB_INVALID_FILE,
                    "上传文件为空，请选择 .md/.markdown/.txt（UTF-8）文件");
        }
        String originalName = file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.getMessage());
            throw new KbAdminException(KbAdminException.KB_INVALID_FILE, "读取上传文件失败");
        }
        List<String> tagList = parseTagsParam(tags);
        KbDocumentView view = KbDocumentView.from(service().upload(originalName, bytes,
                project, tagList));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * 文档列表（不含原文，id 倒序，新上传在前）。
     * 迭代10：可选 query project（等值）/ tag（单标签包含），组合 AND；不传 = 全量。
     */
    @GetMapping("/documents")
    public List<KbDocumentView> listDocuments(
            @RequestParam(value = "project", required = false) String project,
            @RequestParam(value = "tag", required = false) String tag) {
        return service().list(project, tag).stream().map(KbDocumentView::from).toList();
    }

    /**
     * 全量替换文档维度元数据（迭代10）：body 必含 project（可 null=清除）与 tags（可空数组）；
     * 404 KB_NOT_FOUND 沿用；非法值 400 KB_INVALID_PROJECT/KB_INVALID_TAGS。
     */
    @PatchMapping("/documents/{id}")
    public KbDocumentView updateMeta(@PathVariable Long id,
                                     @RequestBody KbDocumentMetaPatch patch) {
        if (patch == null) {
            throw new KbAdminException(KbAdminException.KB_INVALID_TAGS,
                    "请求体为空：需含 project（可 null）与 tags（可空数组）");
        }
        return KbDocumentView.from(service().updateMeta(id, patch.project(), patch.tags()));
    }

    /** 删除文档（FK 级联片段）；不存在 404 KB_NOT_FOUND；成功 204。 */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id) {
        service().delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 用已存原文重建切片与向量；失败置 FAILED 并 502。维度元数据保留不变。 */
    @PostMapping("/documents/{id}/reindex")
    public KbDocumentView reindex(@PathVariable Long id) {
        return KbDocumentView.from(service().reindex(id));
    }

    /**
     * 表单 tags 逗号分隔纯解析（split+trim+去空，不校验）——完整校验在 service 侧
     * 统一经 KbMetaValidator（上限配置在 RagProperties，开关关闭时该 bean 不绑定，
     * 控制器常驻装配不可直接依赖）。
     */
    private static List<String> parseTagsParam(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> list = new java.util.ArrayList<>();
        for (String part : tags.split(",")) {
            if (part != null && !part.isBlank()) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private KbDocumentService service() {
        KbDocumentService service = serviceProvider.getIfAvailable();
        if (service == null) {
            // 正常不可达：开关关闭时拦截器已先返 503 KB_DISABLED
            throw new KbAdminException(KbAdminException.KB_DISABLED, "知识库功能未启用");
        }
        return service;
    }
}
