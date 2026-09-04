package com.dj.ai.agentchat.tool.admin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.ToolNotFoundException;
import com.dj.ai.agentchat.exception.ToolsUnavailableException;
import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.admin.dto.PageResult;
import com.dj.ai.agentchat.tool.admin.dto.ToolCallLogView;
import com.dj.ai.agentchat.tool.admin.dto.ToolDetail;
import com.dj.ai.agentchat.tool.admin.dto.ToolListItem;
import com.dj.ai.agentchat.tool.admin.dto.ToolUpsertRequest;
import com.dj.ai.agentchat.tool.handler.builtin.BuiltinTool;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import com.dj.ai.agentchat.tool.security.PathGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 管理端工具服务（插入迭代 G，T10）：{@code /api/admin/tools} 全 CRUD +
 * {@code /api/admin/tool-call-logs} 分页查询。
 *
 * <p>语义要点（AC-42~48）：
 * <ul>
 *   <li>校验全部在本类集中：name 正则/不可改/重名、description、inputSchema 轻量 JSON Schema、
 *       handlerType 仅 BUILTIN/SCRIPT（HTTP/SCRIPT_DB 拒绝）、handler_config bean/脚本存在性、
 *       timeout/output 区间、分页与时间参数；非法一律 {@link InvalidChatRequestException}（400），
 *       message 含字段名；</li>
 *   <li>写操作成功后 {@link ToolRegistry#refresh()}；refresh 失败抛
 *       {@link ToolsUnavailableException}（503，数据已落库）；</li>
 *   <li>Mapper 抛 {@link DataAccessException} 统一转 503 {@code TOOLS_UNAVAILABLE}
 *       （管理端要求强一致，不做对话链路的空集降级）；</li>
 *   <li>写操作仅 INFO 记录操作类型 + tool_name，不打 token/guide/config 全文（AC-48）。</li>
 * </ul>
 */
@Slf4j
public class ToolAdminService {

    /** 工具名正则：小写字母开头，小写字母/数字/下划线，总长 2-64。 */
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final int DESCRIPTION_MAX = 2000;
    private static final int TIMEOUT_MIN = 1;
    private static final int TIMEOUT_MAX = 60000;
    private static final int OUTPUT_MIN = 100;
    private static final int OUTPUT_MAX = 100000;
    private static final int PAGE_SIZE_MAX = 100;

    private final AgentToolMapper toolMapper;
    private final AgentToolCallLogMapper logMapper;
    private final ToolRegistry registry;
    private final Map<String, BuiltinTool> builtins;
    private final ToolProperties properties;

    public ToolAdminService(AgentToolMapper toolMapper,
                            AgentToolCallLogMapper logMapper,
                            ToolRegistry registry,
                            List<BuiltinTool> builtinTools,
                            ToolProperties properties) {
        this.toolMapper = toolMapper;
        this.logMapper = logMapper;
        this.registry = registry;
        this.builtins = new TreeMap<>();
        if (builtinTools != null) {
            for (BuiltinTool tool : builtinTools) {
                builtins.put(tool.key(), tool);
            }
        }
        this.properties = properties;
    }

    // ==================== 查询 ====================

    /** 全部工具（含 disabled），按 id 升序；列表项不含 guide 全文（带 guideLength）。 */
    public List<ToolListItem> listTools() {
        List<AgentToolPO> rows = accessDb(() -> toolMapper.selectList(
                new QueryWrapper<AgentToolPO>().orderByAsc("id")));
        List<ToolListItem> items = new ArrayList<>(rows.size());
        for (AgentToolPO row : rows) {
            items.add(new ToolListItem(
                    row.getId(), row.getToolName(), row.getDescription(), row.getHandlerType(),
                    row.getEnabled(), row.getTimeoutMs(), row.getOutputMaxChars(),
                    row.getGuideMd() == null ? 0 : row.getGuideMd().length(),
                    row.getCreatedAt(), row.getUpdatedAt()));
        }
        return items;
    }

    /** 工具详情（全字段）；不存在 404。 */
    public ToolDetail getTool(Long id) {
        return ToolDetail.from(requireTool(id));
    }

    // ==================== 写操作 ====================

    /** 新增工具；成功后刷新快照。返回详情。 */
    public ToolDetail createTool(ToolUpsertRequest request) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (!TOOL_NAME.matcher(name).matches()) {
            throw invalid("工具名(name)格式非法：需小写字母开头、仅含小写字母/数字/下划线，长度 2-64");
        }
        Long sameName = accessDb(() -> toolMapper.selectCount(
                new QueryWrapper<AgentToolPO>().eq("tool_name", name)));
        if (sameName != null && sameName > 0) {
            throw invalid("工具名(name)已存在: " + name);
        }

        AgentToolPO po = new AgentToolPO();
        po.setToolName(name);
        applyValidatedFields(po, request);
        po.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());

        accessDb(() -> toolMapper.insert(po));
        log.info("管理端新增工具: name={}, handlerType={}", name, po.getHandlerType());
        refreshAfterWrite();
        return getTool(po.getId());
    }

    /**
     * 修改工具。
     *
     * @param partial true=PATCH（null 字段不改）；false=PUT（全量，description/schema/handler
     *                必备字段缺失 → 400）。name 携带且与现名不同一律 400（不可修改）。
     */
    public ToolDetail updateTool(Long id, ToolUpsertRequest request, boolean partial) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        AgentToolPO existing = requireTool(id);
        if (request.name() != null && !request.name().isBlank()
                && !request.name().trim().equals(existing.getToolName())) {
            throw invalid("工具名(name)不可修改");
        }
        if (!partial) {
            // PUT 全量：核心字段必须显式提供
            if (isBlank(request.description())) {
                throw invalid("描述(description)不能为空");
            }
            if (request.inputSchema() == null) {
                throw invalid("入参模式(inputSchema)不能为空");
            }
            if (isBlank(request.handlerType())) {
                throw invalid("处理器类型(handlerType)不能为空");
            }
            if (request.handlerConfig() == null) {
                throw invalid("处理器配置(handlerConfig)不能为空");
            }
        }

        // 合并为「有效请求」后统一校验：PATCH 用现值补缺，PUT 缺失字段已在上面拦截
        ToolUpsertRequest merged = new ToolUpsertRequest(
                existing.getToolName(),
                request.description() != null ? request.description() : existing.getDescription(),
                request.inputSchema() != null ? request.inputSchema()
                        : parseObjectOrNull(existing.getInputSchema()),
                request.handlerType() != null ? request.handlerType() : existing.getHandlerType(),
                request.handlerConfig() != null ? request.handlerConfig()
                        : parseObjectOrNull(existing.getHandlerConfig()),
                request.guideMd() != null ? request.guideMd() : existing.getGuideMd(),
                request.enabled() != null ? request.enabled() : existing.getEnabled(),
                request.timeoutMs() != null ? request.timeoutMs() : existing.getTimeoutMs(),
                request.outputMaxChars() != null ? request.outputMaxChars()
                        : existing.getOutputMaxChars());
        applyValidatedFields(existing, merged);
        existing.setEnabled(merged.enabled() == null ? Boolean.TRUE : merged.enabled());

        accessDb(() -> toolMapper.updateById(existing));
        log.info("管理端修改工具: name={}, partial={}", existing.getToolName(), partial);
        refreshAfterWrite();
        return getTool(id);
    }

    /** 物理删除工具（审计行保留）；不存在 404。 */
    public ToolDetail deleteTool(Long id) {
        AgentToolPO existing = requireTool(id);
        ToolDetail detail = ToolDetail.from(existing);
        accessDb(() -> toolMapper.deleteById(id));
        log.info("管理端删除工具: name={}", existing.getToolName());
        refreshAfterWrite();
        return detail;
    }

    // ==================== 审计查询 ====================

    /**
     * 审计日志分页（时间倒序）。
     *
     * @param page      0 基页码（≥0）
     * @param size      每页条数（1-100）
     * @param toolName  可选：按工具名精确过滤
     * @param sessionId 可选：按会话过滤
     * @param status    可选：SUCCESS/FAILED/TIMEOUT
     * @param from      可选：起始时间（含，created_at）
     * @param to        可选：截止时间（含）
     */
    public PageResult<ToolCallLogView> pageLogs(int page, int size, String toolName, String sessionId,
                                                String status, LocalDateTime from, LocalDateTime to) {
        if (page < 0) {
            throw invalid("分页页码(page)不能为负数");
        }
        if (size < 1 || size > PAGE_SIZE_MAX) {
            throw invalid("分页大小(size)取值范围 1-" + PAGE_SIZE_MAX);
        }
        QueryWrapper<AgentToolCallLogPO> wrapper = new QueryWrapper<>();
        if (!isBlank(toolName)) {
            wrapper.eq("tool_name", toolName.trim());
        }
        if (!isBlank(sessionId)) {
            wrapper.eq("session_id", sessionId.trim());
        }
        if (!isBlank(status)) {
            wrapper.eq("status", status.trim());
        }
        if (from != null) {
            wrapper.ge("created_at", from);
        }
        if (to != null) {
            wrapper.le("created_at", to);
        }
        wrapper.orderByDesc("created_at").orderByDesc("id");

        // MyBatis-Plus Page 为 1 基页码
        Page<AgentToolCallLogPO> result = accessDb(() ->
                logMapper.selectPage(new Page<>(page + 1L, size), wrapper));
        List<ToolCallLogView> views = new ArrayList<>(result.getRecords().size());
        for (AgentToolCallLogPO po : result.getRecords()) {
            views.add(ToolCallLogView.from(po));
        }
        return new PageResult<>(views, result.getTotal(), page, size);
    }

    // ==================== 校验与装配 ====================

    /** 校验并写入除 name/enabled 外的字段（name 已在 create 校验；enabled 由调用方处理）。校验规则新增/修改一致。 */
    private void applyValidatedFields(AgentToolPO po, ToolUpsertRequest request) {
        String description = request.description();
        if (isBlank(description)) {
            throw invalid("描述(description)不能为空");
        }
        String trimmedDescription = description.trim();
        if (trimmedDescription.length() > DESCRIPTION_MAX) {
            throw invalid("描述(description)长度不能超过 " + DESCRIPTION_MAX + " 字符");
        }
        po.setDescription(trimmedDescription);

        JSONObject schema = request.inputSchema();
        if (schema == null || (!schema.containsKey("type") && !schema.containsKey("properties"))) {
            throw invalid("入参模式(inputSchema)必须是包含 type 或 properties 的 JSON 对象");
        }
        po.setInputSchema(JSON.toJSONString(schema));

        String handlerType = request.handlerType();
        if (isBlank(handlerType)) {
            throw invalid("处理器类型(handlerType)不能为空");
        }
        String normalizedType = handlerType.trim().toUpperCase();
        if (HandlerType.HTTP.name().equals(normalizedType) || HandlerType.SCRIPT_DB.name().equals(normalizedType)) {
            throw invalid("处理器类型(handlerType)该类型尚未实现: " + normalizedType);
        }
        HandlerType type;
        try {
            type = HandlerType.valueOf(normalizedType);
        } catch (IllegalArgumentException e) {
            throw invalid("处理器类型(handlerType)非法: " + handlerType);
        }

        JSONObject config = request.handlerConfig();
        if (config == null) {
            throw invalid("处理器配置(handlerConfig)不能为空");
        }
        po.setHandlerType(type.name());
        po.setHandlerConfig(validateAndRenderConfig(type, config));

        po.setGuideMd(request.guideMd());

        Integer timeout = request.timeoutMs();
        if (timeout == null) {
            timeout = properties.getDefaultTimeoutMs();
        } else if (timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
            throw invalid("超时(timeoutMs)取值范围 " + TIMEOUT_MIN + "-" + TIMEOUT_MAX + " 毫秒");
        }
        po.setTimeoutMs(timeout);

        Integer output = request.outputMaxChars();
        if (output == null) {
            output = properties.getDefaultOutputMaxChars();
        } else if (output < OUTPUT_MIN || output > OUTPUT_MAX) {
            throw invalid("结果上限(outputMaxChars)取值范围 " + OUTPUT_MIN + "-" + OUTPUT_MAX);
        }
        po.setOutputMaxChars(output);
    }

    /** 校验 handler_config 并返回落库 JSON 文本。 */
    private String validateAndRenderConfig(HandlerType type, JSONObject config) {
        if (type == HandlerType.BUILTIN) {
            String bean = config.getString("bean");
            if (isBlank(bean)) {
                throw invalid("处理器配置(handlerConfig.bean)不能为空");
            }
            String beanKey = bean.trim();
            if (!builtins.containsKey(beanKey)) {
                throw invalid("内置工具(handlerConfig.bean)不存在: " + beanKey);
            }
            JSONObject rendered = new JSONObject();
            rendered.put("bean", beanKey);
            return JSON.toJSONString(rendered);
        }
        if (type == HandlerType.SCRIPT) {
            String script = config.getString("script");
            if (isBlank(script)) {
                throw invalid("处理器配置(handlerConfig.script)不能为空");
            }
            String fileName = script.trim();
            if (!PathGuard.isSafeFileName(fileName)) {
                throw invalid("脚本文件名(handlerConfig.script)非法：仅允许字母/数字/./_/-，不接受路径");
            }
            Optional<Path> root = PathGuard.realRoot(properties.getScriptDir());
            if (root.isEmpty()) {
                throw invalid("脚本目录(handlerConfig.script)不可用，请确认 script-dir 配置: "
                        + properties.getScriptDir());
            }
            try {
                Path scriptPath = PathGuard.resolveWithin(root.get(), fileName);
                if (!Files.isRegularFile(scriptPath)) {
                    throw invalid("脚本文件(handlerConfig.script)不存在: " + fileName);
                }
            } catch (java.io.IOException e) {
                throw invalid("脚本文件(handlerConfig.script)不存在: " + fileName);
            }
            JSONObject rendered = new JSONObject();
            rendered.put("script", fileName);
            return JSON.toJSONString(rendered);
        }
        // 理论不可达（HTTP/SCRIPT_DB 已前置拒绝）
        throw invalid("处理器类型(handlerType)该类型尚未实现: " + type);
    }

    // ==================== 基础设施 ====================

    private AgentToolPO requireTool(Long id) {
        AgentToolPO po = accessDb(() -> toolMapper.selectById(id));
        if (po == null) {
            throw new ToolNotFoundException("工具不存在: id=" + id);
        }
        return po;
    }

    private void refreshAfterWrite() {
        try {
            registry.refresh();
        } catch (ToolsUnavailableException e) {
            // 数据已落库但快照刷新失败：ERROR 记录后转 503 提示重试（DB 恢复后再写/刷新自愈）
            log.error("工具写库成功但缓存刷新失败", e);
            throw e;
        }
    }

    /** Mapper 访问统一收口：DataAccessException → 503（不外泄 SQL/连接信息）。 */
    private static <T> T accessDb(DbAction<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            log.warn("管理端数据库访问失败: {}", e.getMessage());
            throw new ToolsUnavailableException("工具服务暂不可用（数据库访问失败）", e);
        }
    }

    private static InvalidChatRequestException invalid(String message) {
        return new InvalidChatRequestException(message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static JSONObject parseObjectOrNull(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface DbAction<T> {
        T run();
    }
}
