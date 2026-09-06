package com.dj.ai.agentchat.tool.mcp.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 管理端<b>只读</b>接口（迭代4 T5，FR-6/AC-28/AC-32/AC-35）：
 * 仅 {@code GET /api/admin/mcp/servers}；不存在任何新增/编辑/删除/启停用 endpoint
 * （POST/PUT/PATCH/DELETE 落到同路径返回 405，AC-28）。
 *
 * <p><b>常驻装配</b>：控制器无条件随组件扫描存在——MCP 子开关关闭/服务 bean 缺席时
 * 返回 {@code {"servers":[]}} 而非 500（AC-35）；鉴权复用常驻
 * {@code AdminAuthInterceptor}（/api/admin/** 三层语义，AC-33）。
 * JSON 走 fastjson2 转换器。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/mcp")
public class McpAdminController {

    private final ObjectProvider<AdminMcpService> serviceProvider;

    public McpAdminController(ObjectProvider<AdminMcpService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /** server 只读列表（READY/UNAVAILABLE + 发现工具清单；不含 env）。 */
    @GetMapping("/servers")
    public McpServersResponse servers() {
        AdminMcpService service = serviceProvider.getIfAvailable();
        List<McpServerView> servers = service == null ? List.of() : service.listServers();
        return new McpServersResponse(servers);
    }

    /** 响应包装：固定 {@code {"servers":[...]}} 形态，空列表为 {"servers":[]}。 */
    public record McpServersResponse(List<McpServerView> servers) {
    }
}
