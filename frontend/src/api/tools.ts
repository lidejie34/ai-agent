import { apiFetch } from './http'

// 对话级工具选择器选项（迭代12）：GET /api/tools/available。
// 工具开关关闭/MCP 子开关关闭 → 对应侧空数组，调用方静默隐藏选择器。

export interface AvailableDbTool {
  name: string
  description: string
}

export interface AvailableMcpServer {
  name: string
  status: string
  tools: string[]
}

export interface ToolsAvailableOptions {
  dbTools: AvailableDbTool[]
  mcpServers: AvailableMcpServer[]
}

export async function getAvailableTools(): Promise<ToolsAvailableOptions> {
  return apiFetch<ToolsAvailableOptions>('/api/tools/available')
}
