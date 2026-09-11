package com.dj.ai.agentchat.tool.admin;

/**
 * 管理端路径分派闸门（迭代6）：把 {@code /api/admin/**} 下的请求 URI 映射到所需功能开关，
 * 纯函数无状态，便于单测覆盖三条分派。
 *
 * <ul>
 *   <li>{@code /api/admin/tools[/**]}、{@code /api/admin/mcp[/**]}、
 *       {@code /api/admin/tool-call-logs} → {@link Gate#TOOLS}（app.tools.enabled）；</li>
 *   <li>{@code /api/admin/kb[/**]} → {@link Gate#RAG}（app.rag.enabled）；</li>
 *   <li>其他管理端路径 → {@link Gate#TOKEN_ONLY}（只校验 X-Admin-Token）。</li>
 * </ul>
 * 段边界严格匹配（{@code /api/admin/kb2} 不会被当成知识库路径）。
 */
public final class AdminGateResolver {

    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final String TOOLS_SEGMENT = "tools";
    private static final String MCP_SEGMENT = "mcp";
    private static final String TOOL_LOGS_SEGMENT = "tool-call-logs";
    private static final String KB_SEGMENT = "kb";

    public enum Gate {
        TOOLS,
        RAG,
        TOKEN_ONLY
    }

    public Gate gateFor(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(ADMIN_PREFIX)) {
            return Gate.TOKEN_ONLY;
        }
        String remainder = requestUri.substring(ADMIN_PREFIX.length());
        String firstSegment = remainder.contains("/")
                ? remainder.substring(0, remainder.indexOf('/')) : remainder;
        return switch (firstSegment) {
            case TOOLS_SEGMENT, MCP_SEGMENT, TOOL_LOGS_SEGMENT -> Gate.TOOLS;
            case KB_SEGMENT -> Gate.RAG;
            default -> Gate.TOKEN_ONLY;
        };
    }
}
