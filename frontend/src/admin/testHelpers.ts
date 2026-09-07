import { vi } from 'vitest'
import type { ApiError } from '../types'

// 管理端测试共享设施（迭代 H）：沿用 useSessions.test 的 routeFetch/jsonResponse 模式。

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function apiErrorResponse(code: string, message: string, status: number): Response {
  const err: ApiError = { code, message, timestamp: '2026-09-07 12:00:00' }
  return jsonResponse(err, status)
}

/** 按 URL 包含匹配的路由 fetch mock；未匹配返回 404。 */
export function routeFetch(routes: Record<string, () => Response>): ReturnType<typeof vi.fn> {
  const fn = vi.fn(async (url: string, _init?: RequestInit) => {
    const key = Object.keys(routes).find((k) => url.includes(k))
    if (key) return routes[key]()
    return new Response('not found', { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}
