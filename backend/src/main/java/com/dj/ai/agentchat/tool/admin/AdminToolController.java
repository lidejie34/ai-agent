package com.dj.ai.agentchat.tool.admin;

import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.tool.admin.dto.PageResult;
import com.dj.ai.agentchat.tool.admin.dto.ToolCallLogView;
import com.dj.ai.agentchat.tool.admin.dto.ToolDetail;
import com.dj.ai.agentchat.tool.admin.dto.ToolListItem;
import com.dj.ai.agentchat.tool.admin.dto.ToolUpsertRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工具管理端接口（插入迭代 G，T10）：{@code /api/admin/tools} CRUD +
 * {@code /api/admin/tool-call-logs} 审计分页。
 *
 * <p><b>常驻装配</b>：控制器本身无条件在组件扫描内——{@code app.tools.enabled=false} 时
 * {@link ToolAdminService} bean 不装配，请求先被 {@code AdminAuthInterceptor} 挡为
 * 400 {@code TOOLS_DISABLED}（不会 404，AC-2）；service 缺席仅作防御性 400。
 * 鉴权（X-Admin-Token 三层语义）全部在拦截器，本类不重复鉴权。
 * JSON 走 fastjson2 转换器（record + null 省略）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminToolController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final DateTimeFormatter DATE_TIME_SPACE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectProvider<ToolAdminService> serviceProvider;

    public AdminToolController(ObjectProvider<ToolAdminService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /** 工具列表（含 disabled，id 升序；列表项无 guide 全文）。 */
    @GetMapping("/tools")
    public List<ToolListItem> listTools() {
        return service().listTools();
    }

    /** 工具详情（全字段）；不存在 404 TOOL_NOT_FOUND。 */
    @GetMapping("/tools/{id}")
    public ToolDetail getTool(@PathVariable Long id) {
        return service().getTool(id);
    }

    /** 新增工具；成功 201 返回详情。 */
    @PostMapping(value = "/tools", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ToolDetail> createTool(@RequestBody(required = false) ToolUpsertRequest request) {
        ToolDetail detail = service().createTool(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    /** 全量修改；name 变更 400，不存在 404。 */
    @PutMapping(value = "/tools/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ToolDetail replaceTool(@PathVariable Long id,
                                  @RequestBody(required = false) ToolUpsertRequest request) {
        return service().updateTool(id, request, false);
    }

    /** 部分修改（含 enabled 启停用）；null 字段不改。 */
    @PatchMapping(value = "/tools/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ToolDetail patchTool(@PathVariable Long id,
                                @RequestBody(required = false) ToolUpsertRequest request) {
        return service().updateTool(id, request, true);
    }

    /** 物理删除（审计行保留）；不存在 404。返回被删行详情。 */
    @DeleteMapping("/tools/{id}")
    public ToolDetail deleteTool(@PathVariable Long id) {
        return service().deleteTool(id);
    }

    /**
     * 审计日志分页（时间倒序）：page（≥0，默认 0）、size（1-100，默认 20）、
     * toolName/sessionId/status 精确过滤、from/to（{@code yyyy-MM-dd HH:mm:ss}，
     * 宽松接受 ISO {@code yyyy-MM-dd'T'HH:mm:ss}）。
     */
    @GetMapping("/tool-call-logs")
    public PageResult<ToolCallLogView> toolCallLogs(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "toolName", required = false) String toolName,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return service().pageLogs(parsePage(page), parseSize(size), toolName, sessionId, status,
                parseTime("from", from), parseTime("to", to));
    }

    // ---- 防御性取服务 + 参数解析 ----

    private ToolAdminService service() {
        ToolAdminService service = serviceProvider.getIfAvailable();
        if (service == null) {
            // 正常不可达：开关关闭时拦截器已先返 400 TOOLS_DISABLED
            throw new InvalidChatRequestException("工具功能未启用");
        }
        return service;
    }

    private static int parsePage(String page) {
        if (page == null || page.isBlank()) {
            return DEFAULT_PAGE;
        }
        try {
            return Integer.parseInt(page.trim());
        } catch (NumberFormatException e) {
            throw new InvalidChatRequestException("分页页码(page)必须为非负整数");
        }
    }

    private static int parseSize(String size) {
        if (size == null || size.isBlank()) {
            return DEFAULT_SIZE;
        }
        try {
            return Integer.parseInt(size.trim());
        } catch (NumberFormatException e) {
            throw new InvalidChatRequestException("分页大小(size)必须为 1-100 的整数");
        }
    }

    private static LocalDateTime parseTime(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.contains("T") ? LocalDateTime.parse(trimmed)
                    : LocalDateTime.parse(trimmed, DATE_TIME_SPACE);
        } catch (Exception e) {
            throw new InvalidChatRequestException(
                    "时间参数(" + field + ")格式错误，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
