import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useSessions } from './useSessions'
import type { ApiError, SessionMessageView, SessionSummary } from '../types'

const SID = '123e4567-e89b-12d3-a456-426614174000'
const OTHER = '223e4567-e89b-12d3-a456-426614174001'

function session(id: string, title: string | null): SessionSummary {
  return {
    sessionId: id,
    title,
    createdAt: '2026-09-01 10:00:00',
    updatedAt: '2026-09-03 14:30:00',
    previewRole: 'assistant',
    previewText: '预览',
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function routeFetch(routes: Record<string, () => Response>): ReturnType<typeof vi.fn> {
  // 按键声明顺序匹配：具体路由（如 '/messages'）必须写在 '/api/sessions' 前面，
  // 因为消息 URL（/api/sessions/{id}/messages）也包含 '/api/sessions'。
  const fn = vi.fn(async (url: string) => {
    const key = Object.keys(routes).find((k) => url.includes(k))
    if (key) return routes[key]()
    return new Response('not found', { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

beforeEach(() => {
  vi.useRealTimers()
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useSessions', () => {
  it('初始加载列表；title 为 null 的会话保留（侧边栏兜底「新会话」）', async () => {
    routeFetch({
      '/api/sessions': () =>
        jsonResponse([session(SID, '标题A'), { ...session(OTHER, null), previewRole: null, previewText: null }]),
    })
    const { result } = renderHook(() => useSessions())

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.sessions).toHaveLength(2)
    expect(result.current.sessions[0].title).toBe('标题A')
    expect(result.current.sessions[1].title).toBeNull()
    expect(result.current.loadError).toBe(false)
  })

  it('列表加载失败 → loadError=true，refresh 重试恢复', async () => {
    const fn = vi.fn()
      .mockResolvedValueOnce(new Response('err', { status: 503 }))
      .mockResolvedValueOnce(jsonResponse([session(SID, 'A')]))
    vi.stubGlobal('fetch', fn)
    const { result } = renderHook(() => useSessions())

    await waitFor(() => expect(result.current.loadError).toBe(true))
    await act(async () => {
      await result.current.refresh()
    })
    expect(result.current.loadError).toBe(false)
    expect(result.current.sessions).toHaveLength(1)
  })

  it('selectSession：200 返回升序历史消息', async () => {
    const msgs: SessionMessageView[] = [
      { role: 'user', content: '你好', createdAt: '2026-09-03 14:00:00' },
      { role: 'assistant', content: '你好呀', createdAt: '2026-09-03 14:00:01' },
    ]
    routeFetch({
      '/messages': () => jsonResponse(msgs),
      '/api/sessions': () => jsonResponse([session(SID, 'A')]),
    })
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let res: Awaited<ReturnType<typeof result.current.selectSession>>
    await act(async () => {
      res = await result.current.selectSession(SID)
    })
    expect(res!.kind).toBe('ok')
    if (res!.kind === 'ok') expect(res!.messages).toHaveLength(2)
  })

  it('selectSession：404 SESSION_NOT_FOUND → notfound 并从列表移除', async () => {
    const err: ApiError = { code: 'SESSION_NOT_FOUND', message: '会话不存在', timestamp: '' }
    routeFetch({
      '/messages': () => jsonResponse(err, 404),
      '/api/sessions': () => jsonResponse([session(SID, 'A')]),
    })
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let res: Awaited<ReturnType<typeof result.current.selectSession>>
    await act(async () => {
      res = await result.current.selectSession(SID)
    })
    expect(res!.kind).toBe('notfound')
    expect(result.current.sessions.find((s) => s.sessionId === SID)).toBeUndefined()
  })

  it('removeSession：DELETE 成功后从列表移除', async () => {
    // 每次调用都返回新 Response（同一 Response 的 body 只能读一次）
    const fn = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') return jsonResponse({ deleted: true })
      return jsonResponse([session(SID, 'A')])
    })
    vi.stubGlobal('fetch', fn)
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.removeSession(SID)
    })
    expect(result.current.sessions).toHaveLength(0)
    const deleteCall = fn.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'DELETE')
    expect(deleteCall?.[0]).toContain(`/api/sessions/${SID}`)
  })

  it('renameSession：空白/超长前端拦截（不发请求）；合法 PATCH 后本地更新', async () => {
    const fn = routeFetch({ '/api/sessions': () => jsonResponse([session(SID, '旧标题')]) })
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    const beforePatch = fn.mock.calls.length

    // 空白拦截
    await expect(
      act(async () => {
        await result.current.renameSession(SID, '   ')
      }),
    ).rejects.toThrow()
    // 超长拦截（201 字符）
    await expect(
      act(async () => {
        await result.current.renameSession(SID, '字'.repeat(201))
      }),
    ).rejects.toThrow()
    expect(fn.mock.calls.length).toBe(beforePatch) // 未发 PATCH

    // 合法 PATCH
    fn.mockResolvedValue(jsonResponse({ ...session(SID, '新标题'), previewRole: null, previewText: null }))
    await act(async () => {
      await result.current.renameSession(SID, '  新标题  ')
    })
    const patchCall = fn.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'PATCH')
    expect(patchCall).toBeTruthy()
    expect(JSON.parse((patchCall![1] as RequestInit).body as string)).toEqual({ title: '新标题' })
    expect(result.current.sessions[0].title).toBe('新标题')
  })
})
