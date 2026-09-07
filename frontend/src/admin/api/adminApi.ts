import { adminFetch } from './adminClient'
import type {
  McpServersResponse,
  PageResult,
  ToolCallLog,
  ToolDetail,
  ToolListItem,
  ToolLogQuery,
  ToolUpsertBody,
} from '../types'

// 管理端 7 个端点封装（迭代 H）。全部相对路径（dev proxy / 生产同源，AC-49）。

export const listMcpServers = () => adminFetch<McpServersResponse>('/api/admin/mcp/servers')

export const listTools = () => adminFetch<ToolListItem[]>('/api/admin/tools')

export const getTool = (id: number) => adminFetch<ToolDetail>(`/api/admin/tools/${id}`)

export const createTool = (body: ToolUpsertBody) =>
  adminFetch<ToolDetail>('/api/admin/tools', { method: 'POST', body: JSON.stringify(body) })

/** 编辑/启停：PATCH；编辑携带全字段且不带 name（D10/AC-33）；启停 body 为 { enabled }。 */
export const patchTool = (id: number, body: Partial<ToolUpsertBody>) =>
  adminFetch<ToolDetail>(`/api/admin/tools/${id}`, { method: 'PATCH', body: JSON.stringify(body) })

export const deleteTool = (id: number) =>
  adminFetch<ToolDetail>(`/api/admin/tools/${id}`, { method: 'DELETE' })

/** 审计分页：仅拼非空字段；page 0 基；from/to 为 yyyy-MM-dd HH:mm:ss（AC-38/39/40）。 */
export function pageToolLogs(q: ToolLogQuery) {
  const params = new URLSearchParams()
  params.set('page', String(q.page))
  params.set('size', String(q.size))
  if (q.toolName?.trim()) params.set('toolName', q.toolName.trim())
  if (q.sessionId?.trim()) params.set('sessionId', q.sessionId.trim())
  if (q.status) params.set('status', q.status)
  if (q.handlerType) params.set('handlerType', q.handlerType)
  if (q.from) params.set('from', q.from)
  if (q.to) params.set('to', q.to)
  return adminFetch<PageResult<ToolCallLog>>(`/api/admin/tool-call-logs?${params.toString()}`)
}

/** 登录验证（AC-7）：GET /api/admin/tools 带头；200 即 token 有效。 */
export const verifyAdminToken = () => listTools()
