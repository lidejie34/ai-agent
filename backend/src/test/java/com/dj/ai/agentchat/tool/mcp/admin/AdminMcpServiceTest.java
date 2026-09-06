package com.dj.ai.agentchat.tool.mcp.admin;

import com.dj.ai.agentchat.tool.mcp.McpProperties;
import com.dj.ai.agentchat.tool.mcp.connection.McpClientGateway;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnection;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerConnectionManager;
import com.dj.ai.agentchat.tool.mcp.connection.McpServerStatus;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminMcpService 视图装配单测（迭代4 T5，AC-32/35）：READY/UNAVAILABLE 视图字段、
 * 工具暴露名、env 绝不外泄、lastError 截断。
 */
class AdminMcpServiceTest {

    private McpProperties.ServerSpec spec(String name, String command) {
        McpProperties.ServerSpec spec = new McpProperties.ServerSpec();
        spec.setName(name);
        spec.setCommand(command);
        spec.setArgs(new ArrayList<>(List.of("arg1", "arg2")));
        // 模拟敏感 env：视图绝不可带出
        spec.setEnv(new java.util.HashMap<>(Map.of("API_TOKEN", "SECRET_ENV_VAL")));
        return spec;
    }

    @Test
    void listServers_mapsReadyAndUnavailable_neverExposesEnv() {
        McpClientGateway gateway = mock(McpClientGateway.class);
        McpServerConnection ready = new McpServerConnection("my-fs", spec("my-fs", "/bin/npx"),
                gateway,
                List.of(new McpSchema.Tool("Read-File", null, "读取文件", null, null, null, null)),
                McpServerStatus.READY, null);
        McpServerConnection down = new McpServerConnection("ghost", spec("ghost", "/bin/node"),
                null, List.of(), McpServerStatus.UNAVAILABLE, "握手失败: 启动即退");

        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.connections()).thenReturn(List.of(ready, down));

        List<McpServerView> views = new AdminMcpService(manager).listServers();

        assertThat(views).hasSize(2);
        McpServerView readyView = views.get(0);
        assertThat(readyView.status()).isEqualTo("READY");
        assertThat(readyView.toolCount()).isEqualTo(1);
        assertThat(readyView.tools().get(0).name()).isEqualTo("my_fs_read_file");
        assertThat(readyView.tools().get(0).rawName()).isEqualTo("Read-File");
        assertThat(readyView.tools().get(0).description()).isEqualTo("读取文件");
        assertThat(readyView.command()).isEqualTo("/bin/npx");
        assertThat(readyView.args()).containsExactly("arg1", "arg2");
        assertThat(readyView.connectedAt()).isNotNull();
        assertThat(readyView.lastError()).isNull();

        McpServerView downView = views.get(1);
        assertThat(downView.status()).isEqualTo("UNAVAILABLE");
        assertThat(downView.toolCount()).isZero();
        assertThat(downView.tools()).isEmpty();
        assertThat(downView.connectedAt()).isNull();
        assertThat(downView.lastError()).contains("握手失败");

        // 视图类型本身无 env 字段（记录组件列表断言，防回潮）
        assertThat(McpServerView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("env", "environment");
    }

    @Test
    void listServers_longLastError_truncated() {
        String longError = "X".repeat(800);
        McpServerConnection down = new McpServerConnection("ghost", spec("ghost", "/bin/x"),
                null, List.of(), McpServerStatus.UNAVAILABLE, longError);
        McpServerConnectionManager manager = mock(McpServerConnectionManager.class);
        when(manager.connections()).thenReturn(List.of(down));

        McpServerView view = new AdminMcpService(manager).listServers().get(0);

        assertThat(view.lastError()).hasSizeLessThan(800).contains("已截断");
    }
}
