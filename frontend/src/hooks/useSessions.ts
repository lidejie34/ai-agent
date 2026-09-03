import { useCallback, useEffect, useState } from 'react'
import * as sessionsApi from '../api/sessions'
import type { ApiError, SessionMessageView, SessionSummary } from '../types'
import { validateRenameTitle } from '../utils/title'

/** 切换会话时历史加载结果：'notfound' 表示会话已被删除（侧边栏移除、回空态）。 */
export type SelectResult =
  | { kind: 'ok'; messages: SessionMessageView[] }
  | { kind: 'notfound' }
  | { kind: 'error' }

function isApiError(e: unknown): e is ApiError {
  return !!e && typeof e === 'object' && typeof (e as ApiError).code === 'string'
}

/**
 * 侧边栏会话数据（FR-16）：服务端为权威源；进入页面/新建/删除/重命名/每轮 done 后 refresh。
 * 列表失败局部重试，不阻断聊天主区（FR-17.4）。
 */
export function useSessions() {
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    setLoadError(false)
    try {
      setSessions(await sessionsApi.listSessions())
    } catch {
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const upsertSession = useCallback((updated: SessionSummary) => {
    setSessions((prev) => {
      const exists = prev.some((s) => s.sessionId === updated.sessionId)
      // 新建/重命名后本地置顶（与后端 updated_at 倒序一致），后台 refresh 兜底
      const next = exists
        ? prev.map((s) => (s.sessionId === updated.sessionId ? { ...s, ...updated } : s))
        : [updated, ...prev]
      return next
    })
  }, [])

  /** 切换：拉取升序历史；404 → 移除该项并回 'notfound'（FR-17.2）；其他失败 → 'error'。 */
  const selectSession = useCallback(
    async (id: string): Promise<SelectResult> => {
      try {
        const messages = await sessionsApi.getMessages(id)
        return { kind: 'ok', messages }
      } catch (e) {
        if (isApiError(e) && e.code === 'SESSION_NOT_FOUND') {
          setSessions((prev) => prev.filter((s) => s.sessionId !== id))
          return { kind: 'notfound' }
        }
        return { kind: 'error' }
      }
    },
    [],
  )

  /** 删除：DELETE 成功后从列表移除；失败抛错由调用方提示。 */
  const removeSession = useCallback(async (id: string): Promise<void> => {
    await sessionsApi.deleteSession(id)
    setSessions((prev) => prev.filter((s) => s.sessionId !== id))
  }, [])

  /** 重命名：前端先校验（空白/超长拦截，FR-16.5），PATCH 成功后本地更新。 */
  const renameSession = useCallback(
    async (id: string, rawTitle: string): Promise<void> => {
      const v = validateRenameTitle(rawTitle)
      if (!v.ok) {
        throw new Error(v.error)
      }
      const updated = await sessionsApi.renameSession(id, v.title)
      upsertSession(updated)
    },
    [upsertSession],
  )

  return {
    sessions,
    loading,
    loadError,
    refresh,
    selectSession,
    removeSession,
    renameSession,
  }
}

export type UseSessionsReturn = ReturnType<typeof useSessions>
