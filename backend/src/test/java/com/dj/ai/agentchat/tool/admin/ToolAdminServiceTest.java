package com.dj.ai.agentchat.tool.admin;

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
import com.dj.ai.agentchat.tool.handler.ToolExecutionContext;
import com.dj.ai.agentchat.tool.handler.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.builtin.BuiltinTool;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T10：{@link ToolAdminService} 校验矩阵 / CRUD / 写后刷新 / 审计分页 / DB 异常 503。
 * 全离线 Mockito（mapper/registry 均为 mock，脚本存在性用 @TempDir 真实文件）。
 */
class ToolAdminServiceTest {

    @TempDir
    Path tempDir;

    private AgentToolMapper toolMapper;
    private AgentToolCallLogMapper logMapper;
    private ToolRegistry registry;
    private ToolProperties properties;
    private ToolAdminService service;

    /** insert 后模拟 MP 回填 id 并留存 PO，selectById 返回留存行。 */
    private AgentToolPO inserted;

    @BeforeEach
    void setUp() {
        toolMapper = mock(AgentToolMapper.class);
        logMapper = mock(AgentToolCallLogMapper.class);
        registry = mock(ToolRegistry.class);
        properties = new ToolProperties();
        properties.setScriptDir(tempDir.toString());
        BuiltinTool builtin = new BuiltinTool() {
            @Override
            public String key() {
                return "analyzeLogErrors";
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext ctx) {
                return ToolExecutionResult.success("ok");
            }
        };
        service = new ToolAdminService(toolMapper, logMapper, registry, List.of(builtin), properties);

        when(toolMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inserted = inv.getArgument(0);
            inserted.setId(100L);
            return 1;
        }).when(toolMapper).insert(any(AgentToolPO.class));
        when(toolMapper.selectById(any())).thenAnswer(inv -> inserted != null ? inserted : null);
    }

    // ---------- 构造合法请求 ----------

    private ToolUpsertRequest validBuiltin(String name) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", new JSONObject());
        JSONObject config = new JSONObject();
        config.put("bean", "analyzeLogErrors");
        return new ToolUpsertRequest(name, "分析日志错误", schema, "BUILTIN", config,
                "操作指南全文", true, null, null);
    }

    private ToolUpsertRequest with(ToolUpsertRequest base, String name, String description,
                                   JSONObject schema, String handlerType, JSONObject config,
                                   Boolean enabled, Integer timeout, Integer output) {
        return new ToolUpsertRequest(name != null ? name : base.name(),
                description != null ? description : base.description(),
                schema != null ? schema : base.inputSchema(),
                handlerType != null ? handlerType : base.handlerType(),
                config != null ? config : base.handlerConfig(),
                base.guideMd(), enabled, timeout, output);
    }

    private JSONObject schemaObject() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        return schema;
    }

    private JSONObject beanConfig(String bean) {
        JSONObject config = new JSONObject();
        config.put("bean", bean);
        return config;
    }

    private JSONObject scriptConfig(String script) {
        JSONObject config = new JSONObject();
        config.put("script", script);
        return config;
    }

    // ---------- 创建：成功路径 ----------

    @Test
    void create_validBuiltin_insertsWithDefaults_andRefreshes() {
        ToolDetail detail = service.createTool(validBuiltin("analyze_log"));

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(toolMapper).insert(captor.capture());
        AgentToolPO po = captor.getValue();
        assertThat(po.getToolName()).isEqualTo("analyze_log");
        assertThat(po.getHandlerType()).isEqualTo("BUILTIN");
        assertThat(po.getEnabled()).isTrue();
        // 缺省值落库
        assertThat(po.getTimeoutMs()).isEqualTo(30000);
        assertThat(po.getOutputMaxChars()).isEqualTo(8000);
        // JSON 列存文本
        assertThat(po.getInputSchema()).contains("\"type\"").contains("object");
        assertThat(po.getHandlerConfig()).contains("analyzeLogErrors");
        assertThat(po.getGuideMd()).isEqualTo("操作指南全文");
        verify(registry).refresh();
        assertThat(detail.name()).isEqualTo("analyze_log");
        assertThat(detail.inputSchema().getString("type")).isEqualTo("object");
    }

    @Test
    void create_validScript_whenFileExists() throws Exception {
        Files.writeString(tempDir.resolve("log_error_count.sh"), "#!/bin/sh\necho ok\n");
        ToolDetail detail = service.createTool(
                with(validBuiltin("log_error_count"), "log_error_count", null, null,
                        "SCRIPT", scriptConfig("log_error_count.sh"), null, null, null));

        assertThat(detail.handlerType()).isEqualTo("SCRIPT");
        assertThat(detail.handlerConfig().getString("script")).isEqualTo("log_error_count.sh");
        verify(registry).refresh();
    }

    @Test
    void create_explicitLimits_persisted() {
        service.createTool(with(validBuiltin("custom_tool"), null, null, null, null, null,
                false, 5000, 4000));

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(toolMapper).insert(captor.capture());
        assertThat(captor.getValue().getTimeoutMs()).isEqualTo(5000);
        assertThat(captor.getValue().getOutputMaxChars()).isEqualTo(4000);
        assertThat(captor.getValue().getEnabled()).isFalse();
    }

    // ---------- 创建：校验矩阵（400，message 含字段名） ----------

    @Test
    void create_nameMissingOrIllegal_400() {
        assertInvalid(() -> service.createTool(validBuiltin(null)));
        assertInvalid(() -> service.createTool(validBuiltin("  ")));
        assertInvalid(() -> service.createTool(validBuiltin("1abc")));   // 数字开头
        assertInvalid(() -> service.createTool(validBuiltin("AnalyzeLog"))); // 大写
        assertInvalid(() -> service.createTool(validBuiltin("a")));      // 太短
        assertInvalid(() -> service.createTool(validBuiltin("a" + "b".repeat(64)))); // 65 字符
        verify(toolMapper, never()).insert(any());
    }

    @Test
    void create_duplicateName_400() {
        when(toolMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createTool(validBuiltin("analyze_log")))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("已存在");
        verify(registry, never()).refresh();
    }

    @Test
    void create_descriptionBlankOrTooLong_400() {
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_one"), null, "   ", null, null, null, null, null, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_two"), null, "x".repeat(2001), null, null, null, null, null, null)));
    }

    @Test
    void create_schemaInvalid_400() {
        // schema 为 null
        assertInvalid(() -> service.createTool(
                new ToolUpsertRequest("t_one", "d", null, "BUILTIN", beanConfig("analyzeLogErrors"),
                        null, true, null, null)));
        // schema 缺 type/properties
        JSONObject bad = new JSONObject();
        bad.put("foo", 1);
        assertInvalid(() -> service.createTool(
                new ToolUpsertRequest("t_three", "d", bad, "BUILTIN", beanConfig("analyzeLogErrors"),
                        null, true, null, null)));
    }

    @Test
    void create_handlerTypeReservedOrUnknown_400() {
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_http"), null, null, null, "HTTP", null, null, null, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_sdb"), null, null, null, "SCRIPT_DB", null, null, null, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_bogus"), null, null, null, "WEBSOCKET", null, null, null, null)));
    }

    @Test
    void create_builtinBeanMissingOrUnknown_400() {
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_nobean"), null, null, null, null, new JSONObject(), null, null, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_unknownbean"), null, null, null, null, beanConfig("noSuchBean"),
                        null, null, null)));
    }

    @Test
    void create_scriptUnsafeNameOrMissing_400() {
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_escape"), null, null, null, "SCRIPT",
                        scriptConfig("../evil.sh"), null, null, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_missing"), null, null, null, "SCRIPT",
                        scriptConfig("nope.sh"), null, null, null)));
        // 脚本目录不存在
        properties.setScriptDir(tempDir.resolve("no-such-dir").toString());
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_nodir"), null, null, null, "SCRIPT",
                        scriptConfig("x.sh"), null, null, null)));
    }

    @Test
    void create_limitsOutOfRange_400() {
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_to1"), null, null, null, null, null, null, 0, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_to2"), null, null, null, null, null, null, 60001, null)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_oo1"), null, null, null, null, null, null, null, 99)));
        assertInvalid(() -> service.createTool(
                with(validBuiltin("t_oo2"), null, null, null, null, null, null, null, 100001)));
    }

    @Test
    void create_dbFailure_throws503() {
        when(toolMapper.selectCount(any())).thenThrow(new QueryTimeoutException("boom"));
        assertThatThrownBy(() -> service.createTool(validBuiltin("analyze_log")))
                .isInstanceOf(ToolsUnavailableException.class);
    }

    @Test
    void create_refreshFailure_propagates503_dataAlreadyPersisted() {
        doThrow(new ToolsUnavailableException("刷新失败")).when(registry).refresh();
        assertThatThrownBy(() -> service.createTool(validBuiltin("analyze_log")))
                .isInstanceOf(ToolsUnavailableException.class);
        // 数据已落库
        verify(toolMapper).insert(any(AgentToolPO.class));
    }

    // ---------- 查询 ----------

    @Test
    void list_includesDisabled_withGuideLength_withoutFullGuide() {
        AgentToolPO enabled = row(1L, "analyze_log", true, "12345");
        AgentToolPO disabled = row(2L, "log_error_count", false, null);
        when(toolMapper.selectList(any())).thenReturn(List.of(enabled, disabled));

        List<ToolListItem> items = service.listTools();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).guideLength()).isEqualTo(5);
        assertThat(items.get(0).enabled()).isTrue();
        assertThat(items.get(1).enabled()).isFalse();
        assertThat(items.get(1).guideLength()).isZero();
    }

    @Test
    void list_dbFailure_throws503() {
        when(toolMapper.selectList(any())).thenThrow(new QueryTimeoutException("boom"));
        assertThatThrownBy(() -> service.listTools()).isInstanceOf(ToolsUnavailableException.class);
    }

    @Test
    void detail_notFound_throws404() {
        when(toolMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getTool(999L))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void detail_found_parsesJsonColumns() {
        inserted = row(7L, "analyze_log", true, "指南全文");
        ToolDetail detail = service.getTool(7L);

        assertThat(detail.id()).isEqualTo(7L);
        assertThat(detail.name()).isEqualTo("analyze_log");
        assertThat(detail.guideMd()).isEqualTo("指南全文");
        assertThat(detail.inputSchema().getString("type")).isEqualTo("object");
        assertThat(detail.handlerConfig().getString("bean")).isEqualTo("analyzeLogErrors");
    }

    // ---------- 修改 / 删除 ----------

    @Test
    void update_put_notFound_404() {
        when(toolMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> service.updateTool(404L, validBuiltin("analyze_log"), false))
                .isInstanceOf(ToolNotFoundException.class);
        verify(registry, never()).refresh();
    }

    @Test
    void update_nameChange_400_forPutAndPatch() {
        when(toolMapper.selectById(any())).thenReturn(row(1L, "old_name", true, null));
        assertInvalid(() -> service.updateTool(1L, validBuiltin("new_name"), false));
        assertInvalid(() -> service.updateTool(1L, validBuiltin("new_name"), true));
        verify(toolMapper, never()).updateById(any());
    }

    @Test
    void update_put_fullValid_updatesAndRefreshes() {
        AgentToolPO existing = row(1L, "analyze_log", true, "旧指南");
        when(toolMapper.selectById(any())).thenReturn(existing);

        ToolUpsertRequest req = with(validBuiltin("analyze_log"), null, "新的描述文本",
                schemaObject(), null, beanConfig("analyzeLogErrors"), null, 12000, null);
        ToolDetail detail = service.updateTool(1L, req, false);

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(toolMapper).updateById(captor.capture());
        AgentToolPO saved = captor.getValue();
        assertThat(saved.getDescription()).isEqualTo("新的描述文本");
        assertThat(saved.getTimeoutMs()).isEqualTo(12000);
        assertThat(saved.getGuideMd()).isEqualTo("操作指南全文");
        verify(registry).refresh();
        assertThat(detail.description()).isEqualTo("新的描述文本");
    }

    @Test
    void update_put_missingRequiredFields_400() {
        when(toolMapper.selectById(any())).thenReturn(row(1L, "analyze_log", true, null));
        // PUT 只给 name（与现名相同），缺 description/schema/handler
        ToolUpsertRequest sparse = new ToolUpsertRequest("analyze_log", null, null, null, null,
                null, null, null, null);
        assertInvalid(() -> service.updateTool(1L, sparse, false));
        verify(toolMapper, never()).updateById(any());
    }

    @Test
    void update_patch_enabledFalse_preservesOtherFields_andRefreshes() {
        AgentToolPO existing = row(1L, "analyze_log", true, "指南保留");
        when(toolMapper.selectById(any())).thenReturn(existing);

        ToolUpsertRequest patch = new ToolUpsertRequest(null, null, null, null, null, null,
                false, null, null);
        service.updateTool(1L, patch, true);

        ArgumentCaptor<AgentToolPO> captor = ArgumentCaptor.forClass(AgentToolPO.class);
        verify(toolMapper).updateById(captor.capture());
        AgentToolPO saved = captor.getValue();
        assertThat(saved.getEnabled()).isFalse();
        // PATCH 未携带的字段保持现值
        assertThat(saved.getDescription()).isEqualTo("分析日志错误");
        assertThat(saved.getGuideMd()).isEqualTo("指南保留");
        assertThat(saved.getHandlerType()).isEqualTo("BUILTIN");
        assertThat(saved.getTimeoutMs()).isEqualTo(30000);
        verify(registry).refresh();
    }

    @Test
    void update_patch_dbFailure_503() {
        when(toolMapper.selectById(any())).thenReturn(row(1L, "analyze_log", true, null));
        doThrow(new QueryTimeoutException("boom")).when(toolMapper).updateById(any());
        assertThatThrownBy(() -> service.updateTool(1L,
                new ToolUpsertRequest(null, null, null, null, null, null, false, null, null), true))
                .isInstanceOf(ToolsUnavailableException.class);
    }

    @Test
    void delete_notFound_404() {
        when(toolMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> service.deleteTool(404L))
                .isInstanceOf(ToolNotFoundException.class);
        verify(toolMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void delete_removesRow_andRefreshes_returnsDeletedDetail() {
        when(toolMapper.selectById(any())).thenReturn(row(9L, "analyze_log", true, "指南"));

        ToolDetail deleted = service.deleteTool(9L);

        verify(toolMapper).deleteById(9L);
        verify(registry).refresh();
        assertThat(deleted.id()).isEqualTo(9L);
        assertThat(deleted.name()).isEqualTo("analyze_log");
    }

    // ---------- 审计分页 ----------

    @Test
    @SuppressWarnings("unchecked")
    void logs_paging_mapsViews_descendingOrder() {
        AgentToolCallLogPO po = new AgentToolCallLogPO();
        po.setId(3L);
        po.setCallId("req-1|analyze_log|abc");
        po.setToolName("analyze_log");
        po.setStatus("SUCCESS");
        po.setDurationMs(42L);
        when(logMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<AgentToolCallLogPO> page = inv.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(1L); // 0 基入参 → MP 1 基
            page.setRecords(List.of(po));
            page.setTotal(1L);
            return page;
        });

        PageResult<ToolCallLogView> result = service.pageLogs(0, 20, "analyze_log", null,
                "SUCCESS", LocalDateTime.now().minusDays(1), LocalDateTime.now());

        assertThat(result.content()).hasSize(1);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.content().get(0).toolName()).isEqualTo("analyze_log");
        assertThat(result.content().get(0).callId()).isEqualTo("req-1|analyze_log|abc");

        ArgumentCaptor<QueryWrapper<AgentToolCallLogPO>> wrapperCaptor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(logMapper).selectPage(any(), wrapperCaptor.capture());
        String segment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(segment).contains("tool_name").contains("status")
                .contains("created_at >=").contains("created_at <=")
                .contains("ORDER BY created_at DESC");
    }

    @Test
    void logs_badPaging_400() {
        assertInvalid(() -> service.pageLogs(-1, 20, null, null, null, null, null));
        assertInvalid(() -> service.pageLogs(0, 0, null, null, null, null, null));
        assertInvalid(() -> service.pageLogs(0, 101, null, null, null, null, null));
        verify(logMapper, never()).selectPage(any(), any());
    }

    @Test
    void logs_dbFailure_503() {
        when(logMapper.selectPage(any(), any())).thenThrow(new QueryTimeoutException("boom"));
        assertThatThrownBy(() -> service.pageLogs(0, 20, null, null, null, null, null))
                .isInstanceOf(ToolsUnavailableException.class);
    }

    // ---------- helpers ----------

    private AgentToolPO row(Long id, String name, boolean enabled, String guide) {
        AgentToolPO po = new AgentToolPO();
        po.setId(id);
        po.setToolName(name);
        po.setDescription("分析日志错误");
        po.setInputSchema("{\"type\":\"object\"}");
        po.setHandlerType("BUILTIN");
        po.setHandlerConfig("{\"bean\":\"analyzeLogErrors\"}");
        po.setGuideMd(guide);
        po.setEnabled(enabled);
        po.setTimeoutMs(30000);
        po.setOutputMaxChars(8000);
        return po;
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(InvalidChatRequestException.class);
    }
}
