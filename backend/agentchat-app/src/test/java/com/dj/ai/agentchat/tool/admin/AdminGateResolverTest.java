package com.dj.ai.agentchat.tool.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9（迭代6）：AdminGateResolver 路径分派——tools/mcp/tool-call-logs 段 → TOOLS，
 * kb 段 → RAG，其余/非管理端/段边界伪造 → TOKEN_ONLY。
 */
class AdminGateResolverTest {

    private final AdminGateResolver resolver = new AdminGateResolver();

    @Test
    void toolsFamily_routesToToolsGate() {
        assertThat(resolver.gateFor("/api/admin/tools")).isEqualTo(AdminGateResolver.Gate.TOOLS);
        assertThat(resolver.gateFor("/api/admin/tools/123")).isEqualTo(AdminGateResolver.Gate.TOOLS);
        assertThat(resolver.gateFor("/api/admin/mcp")).isEqualTo(AdminGateResolver.Gate.TOOLS);
        assertThat(resolver.gateFor("/api/admin/mcp/servers/x")).isEqualTo(AdminGateResolver.Gate.TOOLS);
        assertThat(resolver.gateFor("/api/admin/tool-call-logs"))
                .isEqualTo(AdminGateResolver.Gate.TOOLS);
    }

    @Test
    void kbFamily_routesToRagGate() {
        assertThat(resolver.gateFor("/api/admin/kb")).isEqualTo(AdminGateResolver.Gate.RAG);
        assertThat(resolver.gateFor("/api/admin/kb/documents")).isEqualTo(AdminGateResolver.Gate.RAG);
        assertThat(resolver.gateFor("/api/admin/kb/health")).isEqualTo(AdminGateResolver.Gate.RAG);
    }

    @Test
    void otherAdminPaths_areTokenOnly() {
        assertThat(resolver.gateFor("/api/admin/other")).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
        assertThat(resolver.gateFor("/api/admin")).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
        assertThat(resolver.gateFor(null)).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
    }

    @Test
    void segmentBoundary_isStrict_lookalikePrefixesDoNotMatch() {
        // 段边界：kb2/tools-x 等伪造前缀不得误判
        assertThat(resolver.gateFor("/api/admin/kb2")).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
        assertThat(resolver.gateFor("/api/admin/toolsBackup"))
                .isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
    }

    @Test
    void nonAdminPaths_areTokenOnly() {
        assertThat(resolver.gateFor("/api/chat/sync")).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
        assertThat(resolver.gateFor("/api/sessions")).isEqualTo(AdminGateResolver.Gate.TOKEN_ONLY);
    }
}
