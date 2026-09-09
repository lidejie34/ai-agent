package com.dj.ai.agentchat.tool.admin;

import com.dj.ai.agentchat.tool.ToolProperties;
import com.dj.ai.agentchat.tool.audit.ToolAuditService;
import com.dj.ai.agentchat.tool.admin.dto.ToolDetail;
import com.dj.ai.agentchat.tool.admin.dto.ToolUpsertRequest;
import com.dj.ai.agentchat.tool.callback.ToolCallbackFactory;
import com.dj.ai.agentchat.tool.spi.ToolExecutionContext;
import com.dj.ai.agentchat.tool.spi.ToolExecutionResult;
import com.dj.ai.agentchat.tool.handler.ToolHandler;
import com.dj.ai.agentchat.tool.handler.ToolHandlerRouter;
import com.dj.ai.agentchat.tool.spi.BuiltinTool;
import com.dj.ai.agentchat.tool.mapper.AgentToolCallLogMapper;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.registry.HandlerType;
import com.dj.ai.agentchat.tool.registry.ToolRegistry;
import com.dj.ai.agentchat.tool.schema.ToolSchemaInitializer;
import com.dj.ai.agentchat.tool.security.SecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T7/T10 联动（AC-36）：管理端修改 guide_md → 写后 {@link ToolRegistry#refresh()} →
 * 新装配回调的 ToolDefinition.description 携带<b>新指南</b>（调用前对模型可见）；
 * 旧快照回调的 description 仍带旧指南（快照替换语义）。结果文本不再含指南。
 *
 * <p>全离线：mapper/schema 为 mock，ToolCallbackFactory/ToolRegistry/
 * ToolAdminService 全部真实组件串联；selectList 第一次返回旧行、第二次返回新行，
 * 模拟「PATCH 落库后刷新读到新数据」。
 */
class ToolAdminGuideRefreshTest {

    private static final String OLD_GUIDE = "旧指南-OLD-GUIDE-MARKER";
    private static final String NEW_GUIDE = "新指南-NEW-GUIDE-MARKER";

    private AgentToolMapper toolMapper;
    private ToolRegistry registry;
    private ToolAdminService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        toolMapper = mock(AgentToolMapper.class);
        AgentToolCallLogMapper logMapper = mock(AgentToolCallLogMapper.class);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "tool-joint-test");
            t.setDaemon(true);
            return t;
        });

        // 真实回调工厂：路由到 BUILTIN stub 处理器（固定返回结果体）
        ToolHandler stubHandler = new ToolHandler() {
            @Override
            public HandlerType type() {
                return HandlerType.BUILTIN;
            }

            @Override
            public void validateConfig(String handlerConfigJson) {
            }

            @Override
            public ToolExecutionResult execute(AgentToolPO po, Map<String, Object> args,
                                               ToolExecutionContext ctx) {
                return ToolExecutionResult.success("RESULT-BODY-MARKER");
            }
        };
        ToolHandlerRouter router = new ToolHandlerRouter(List.of(stubHandler));
        ToolAuditService auditService = new ToolAuditService(logMapper);
        SecretRedactor redactor = new SecretRedactor(List.of());
        ToolProperties properties = new ToolProperties();
        ToolCallbackFactory factory = new ToolCallbackFactory(router, auditService, redactor,
                executor, properties);

        ToolSchemaInitializer schemaInitializer = mock(ToolSchemaInitializer.class);
        registry = new ToolRegistry(toolMapper, factory, schemaInitializer);

        // 管理端校验需要 bean 存在
        BuiltinTool builtin = new BuiltinTool() {
            @Override
            public String key() {
                return "demoBeanKey";
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext ctx) {
                return ToolExecutionResult.success("ok");
            }
        };
        service = new ToolAdminService(toolMapper, logMapper, registry, List.of(builtin), properties);

        AgentToolPO v1 = row(OLD_GUIDE);
        AgentToolPO v2 = row(NEW_GUIDE);
        // 首次装载（对话路径）读旧行；PATCH 后 refresh 读新行
        when(toolMapper.selectList(any())).thenReturn(List.of(v1), List.of(v2));
        // requireTool 读旧行（PATCH 前状态），写后 getTool 读新行
        when(toolMapper.selectById(any())).thenReturn(v1, v2);
        when(toolMapper.updateById(any())).thenReturn(1);
    }

    @Test
    void adminGuideChange_refresh_thenCallbackCarriesNewGuide() {
        // 1) 刷新前：工具定义 description 携带旧指南（模型调用前可见），结果文本不含指南
        List<ToolCallback> before = registry.toolCallbacks();
        String descBefore = before.get(0).getToolDefinition().description();
        assertThat(descBefore).contains(OLD_GUIDE).doesNotContain(NEW_GUIDE);
        String outBefore = before.get(0).call("{}",
                new ToolContext(Map.of("requestId", "req-before")));
        assertThat(outBefore).contains("RESULT-BODY-MARKER").doesNotContain(OLD_GUIDE);

        // 2) 管理端 PATCH 仅改 guide_md
        ToolUpsertRequest patch = new ToolUpsertRequest(null, null, null, null, null,
                NEW_GUIDE, null, null, null);
        ToolDetail detail = service.updateTool(1L, patch, true);
        verify(toolMapper).updateById(any());
        assertThat(detail.guideMd()).isEqualTo(NEW_GUIDE);

        // 3) 刷新后：新装配回调的 description 携带新指南，旧标记不再出现
        List<ToolCallback> after = registry.toolCallbacks();
        String descAfter = after.get(0).getToolDefinition().description();
        assertThat(descAfter).contains(NEW_GUIDE).doesNotContain(OLD_GUIDE);
    }

    private static AgentToolPO row(String guide) {
        AgentToolPO po = new AgentToolPO();
        po.setId(1L);
        po.setToolName("analyze_log");
        po.setDescription("分析日志错误");
        po.setInputSchema("{\"type\":\"object\"}");
        po.setHandlerType("BUILTIN");
        po.setHandlerConfig("{\"bean\":\"demoBeanKey\"}");
        po.setGuideMd(guide);
        po.setEnabled(true);
        po.setTimeoutMs(30000);
        po.setOutputMaxChars(8000);
        return po;
    }
}
