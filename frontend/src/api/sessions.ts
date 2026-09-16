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
