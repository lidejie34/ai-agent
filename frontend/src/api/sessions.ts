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
