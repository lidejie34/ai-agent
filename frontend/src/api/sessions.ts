import { apiFetch } from './http'
import type { SessionMessageView, SessionSummary } from '../types'

// 会话管理 REST 封装（相对路径 /api/...；dev proxy 与生产同源反代通用，FR-3/AC-29）。

export async function listSessions(): Promise<SessionSummary[]> {
  return apiFetch<SessionSummary[]>('/api/sessions')
}

export async function getMessages(sessionId: string): Promise<SessionMessageView[]> {
  return apiFetch<SessionMessageView[]>(`/api/sessions/${sessionId}/messages`)
}

export async function deleteSession(sessionId: string): Promise<void> {
  await apiFetch<{ deleted: boolean }>(`/api/sessions/${sessionId}`, { method: 'DELETE' })
}

export async function renameSession(sessionId: string, title: string): Promise<SessionSummary> {
  return apiFetch<SessionSummary>(`/api/sessions/${sessionId}`, {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  })
}

// ---- 会话级范围配置（迭代12）：知识库维度 + 工具/MCP 选择 ----
// 四字段三态：null=默认全部；[]=显式全不选；非空=子集。

// ---- 消息级删除/截断 + 清空上下文 + 批量删会话（迭代13） ----

/** 删除单轮：锚点须为 user 消息（库 id），成对删用户消息 + 其 AI 回复。 */
export async function deleteTurn(sessionId: string, messageId: number): Promise<void> {
  await apiFetch<{ deleted: boolean }>(`/api/sessions/${sessionId}/messages/${messageId}`, {
    method: 'DELETE',
  })
}

/** 从指定消息（库 id）起截断：删除该条及之后全部消息（上下文清空点保留）。 */
export async function truncateMessages(sessionId: string, fromId: number): Promise<void> {
  await apiFetch<{ deleted: boolean }>(`/api/sessions/${sessionId}/messages?fromId=${fromId}`, {
    method: 'DELETE',
  })
}

export interface ContextResetView {
  id: number
  createdAt: string
}

/** 清空上下文但保留记录：返回标记视图（前端就地渲染分隔线/重载历史）。 */
export async function clearContext(sessionId: string): Promise<ContextResetView> {
  return apiFetch<ContextResetView>(`/api/sessions/${sessionId}/context/clear`, { method: 'POST' })
}

export interface BatchSessionDeleteResult {
  deleted: string[]
  notFound: string[]
}

/** 批量删除会话：逐 id 汇报，notFound 不打断整批。 */
export async function batchDeleteSessions(ids: string[]): Promise<BatchSessionDeleteResult> {
  return apiFetch<BatchSessionDeleteResult>('/api/sessions/batch-delete', {
    method: 'POST',
    body: JSON.stringify({ ids }),
  })
}

export interface SessionScope {
  kbProjects: string[] | null
  kbTags: string[] | null
  toolNames: string[] | null
  mcpServers: string[] | null
}

export async function getSessionScope(sessionId: string): Promise<SessionScope> {
  return apiFetch<SessionScope>(`/api/sessions/${sessionId}/scope`)
}

export async function putSessionScope(sessionId: string, scope: SessionScope): Promise<SessionScope> {
  return apiFetch<SessionScope>(`/api/sessions/${sessionId}/scope`, {
    method: 'PUT',
    body: JSON.stringify(scope),
  })
}
