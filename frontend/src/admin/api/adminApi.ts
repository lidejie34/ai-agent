import { adminFetch, adminUpload } from './adminClient'
import type {
  DimProject,
  DimProjectUpsert,
  DimTagOpResult,
  DimTagView,
  KbDocFilter,
  KbDocument,
  KbDocumentMetaPatch,
  KbHealth,
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

// ---- 知识库（迭代6）----

export const getKbHealth = () => adminFetch<KbHealth>('/api/admin/kb/health')

/** 文档列表（迭代10：可选 project 等值 + tag 单标签包含过滤；全空 = 全量）。 */
export function listKbDocuments(filter?: KbDocFilter) {
  const params = new URLSearchParams()
  if (filter?.project?.trim()) params.set('project', filter.project.trim())
  if (filter?.tag?.trim()) params.set('tag', filter.tag.trim())
  const qs = params.toString()
  return adminFetch<KbDocument[]>(`/api/admin/kb/documents${qs ? `?${qs}` : ''}`)
}

/**
 * 上传 .md/.markdown/.txt（UTF-8）：multipart 字段名 file；201 READY。
 * 迭代10：可选维度打标——project 非空白才附字段；tags 逗号拼接
 * （元素白名单禁逗号，后端按逗号切分还原；规整/校验在服务端）。
 */
export function uploadKbDocument(file: File, meta?: { project?: string; tags?: string[] }) {
  const form = new FormData()
  form.append('file', file)
  if (meta?.project?.trim()) form.append('project', meta.project.trim())
  const tags = (meta?.tags ?? []).map((t) => t.trim()).filter(Boolean)
  if (tags.length > 0) form.append('tags', tags.join(','))
  return adminUpload<KbDocument>('/api/admin/kb/documents', form)
}

/** 迭代10：全量替换文档维度元数据（project=null 清除、tags=[] 清空）；404 → KB_NOT_FOUND。 */
export const patchKbDocumentMeta = (id: number, body: KbDocumentMetaPatch) =>
  adminFetch<KbDocument>(`/api/admin/kb/documents/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })

export const deleteKbDocument = (id: number) =>
  adminFetch<void>(`/api/admin/kb/documents/${id}`, { method: 'DELETE' })

export const reindexKbDocument = (id: number) =>
  adminFetch<KbDocument>(`/api/admin/kb/documents/${id}/reindex`, { method: 'POST' })

// ---- 维度维护（迭代10 追加，/api/admin/dim）----

export const listDimProjects = () => adminFetch<DimProject[]>('/api/admin/dim/projects')

export const createDimProject = (body: DimProjectUpsert) =>
  adminFetch<DimProject>('/api/admin/dim/projects', { method: 'POST', body: JSON.stringify(body) })

export const updateDimProject = (id: number, body: DimProjectUpsert) =>
  adminFetch<DimProject>(`/api/admin/dim/projects/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })

export const deleteDimProject = (id: number) =>
  adminFetch<void>(`/api/admin/dim/projects/${id}`, { method: 'DELETE' })

export const listDimTags = () => adminFetch<DimTagView[]>('/api/admin/dim/tags')

export const renameDimTag = (from: string, to: string) =>
  adminFetch<DimTagOpResult>('/api/admin/dim/tags', {
    method: 'PATCH',
    body: JSON.stringify({ from, to }),
  })

export const deleteDimTag = (name: string) =>
  adminFetch<DimTagOpResult>(`/api/admin/dim/tags/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  })
