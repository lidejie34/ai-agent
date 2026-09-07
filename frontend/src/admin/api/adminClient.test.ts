import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { adminFetch } from './adminClient'
import { listMcpServers, listTools, pageToolLogs } from './adminApi'
import { apiFetch } from '../../api/http'
import { clearAdminToken, onUnauthorized, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'

// T1：admin client 切片（头注入/401 广播/错误归一/参数拼装，AC-13/38/39/40/46/49）
const KEY = 'admin.token'

beforeEach(() => {
  clearAdminToken()
  localStorage.removeItem(KEY)
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  localStorage.removeItem(KEY)
})

function stubOk(): ReturnType<typeof vi.fn> {
  const fn = vi.fn(async (_url: string, _init?: RequestInit) => jsonResponse({ ok: true }))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('adminFetch / adminApi', () => {
  it('请求自动注入 X-Admin-Token，值为当前 token；URL 为相对路径（AC-7/13/49）', async () => {
    persistAdminToken('tok-secret')
    const fn = stubOk()
    await adminFetch('/api/admin/tools')
    expect(fn).toHaveBeenCalledTimes(1)
    expect(fn.mock.calls[0][0]).toBe('/api/admin/tools')
    const headers = (fn.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['X-Admin-Token']).toBe('tok-secret')
  })

  it('无 token 时不携带 X-Admin-Token 头（AC-12 退出后）', async () => {
    const fn = stubOk()
    await listTools()
    const headers = (fn.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['X-Admin-Token']).toBeUndefined()
  })

  it('非 admin 请求走既有 apiFetch，绝不携带 X-Admin-Token（AC-13 边界）', async () => {
    persistAdminToken('tok-secret')
    const fn = stubOk()
    await apiFetch('/api/sessions')
    const headers = (fn.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['X-Admin-Token']).toBeUndefined()
    expect(fn.mock.calls[0][0]).toBe('/api/sessions')
  })

  it('401 ADMIN_UNAUTHORIZED：抛 ApiError 并广播 notifyUnauthorized（AC-11/46）', async () => {
    persistAdminToken('tok-secret')
    vi.stubGlobal('fetch', vi.fn(async () => apiErrorResponse('ADMIN_UNAUTHORIZED', '未授权', 401)))
    const sub = vi.fn()
    const unsub = onUnauthorized(sub)
    await expect(adminFetch('/api/admin/tools')).rejects.toMatchObject({ code: 'ADMIN_UNAUTHORIZED' })
    expect(sub).toHaveBeenCalledTimes(1)
    unsub()
  })

  it('400/404/503：错误码与后端 message 原样透传（AC-30/46）', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(apiErrorResponse('BAD_REQUEST', '工具名(name)已存在: dup_tool', 400))
      .mockResolvedValueOnce(apiErrorResponse('TOOL_NOT_FOUND', '工具不存在', 404))
      .mockResolvedValueOnce(apiErrorResponse('TOOLS_UNAVAILABLE', 'db fail', 503))
    vi.stubGlobal('fetch', fn)
    await expect(adminFetch('/api/admin/tools', { method: 'POST', body: '{}' })).rejects.toMatchObject({
      code: 'BAD_REQUEST',
      message: '工具名(name)已存在: dup_tool',
    })
    await expect(adminFetch('/api/admin/tools/1')).rejects.toMatchObject({ code: 'TOOL_NOT_FOUND' })
    await expect(adminFetch('/api/admin/tools/1')).rejects.toMatchObject({ code: 'TOOLS_UNAVAILABLE' })
  })

  it('201 返回 JSON 体；204 返回 undefined', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ id: 9, name: 'new_tool' }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fn)
    expect(await adminFetch('/api/admin/tools', { method: 'POST', body: '{}' })).toEqual({
      id: 9,
      name: 'new_tool',
    })
    expect(await adminFetch('/api/admin/tools/1', { method: 'PATCH', body: '{}' })).toBeUndefined()
  })

  it('fetch 层 reject（断网/CORS）归一为 NETWORK_ERROR（AC-46）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch')
      }),
    )
    await expect(adminFetch('/api/admin/tools')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })

  it('pageToolLogs：仅拼非空参数、page 0 基、from/to 原样拼接（AC-38/39/40）', async () => {
    const fn = stubOk()
    await pageToolLogs({ page: 0, size: 20 })
    expect(fn.mock.calls[0][0]).toBe('/api/admin/tool-call-logs?page=0&size=20')

    await pageToolLogs({
      page: 2,
      size: 50,
      toolName: 'weather',
      sessionId: '  ',
      status: 'FAILED',
      handlerType: 'MCP',
      from: '2026-09-01 00:00:00',
      to: '2026-09-07 23:59:59',
    })
    const url = fn.mock.calls[1][0] as string
    expect(url).toContain('page=2')
    expect(url).toContain('size=50')
    expect(url).toContain('toolName=weather')
    expect(url).toContain('status=FAILED')
    expect(url).toContain('handlerType=MCP')
    expect(url).toContain('from=2026-09-01+00%3A00%3A00')
    expect(url).toContain('to=2026-09-07+23%3A59%3A59')
    expect(url).not.toContain('sessionId') // 空白不拼
  })

  it('listMcpServers 请求 GET /api/admin/mcp/servers（相对路径，AC-19/49）', async () => {
    const fn = stubOk()
    await listMcpServers()
    expect(fn.mock.calls[0][0]).toBe('/api/admin/mcp/servers')
    expect((fn.mock.calls[0][1] as RequestInit).method ?? 'GET').toBe('GET')
  })
})
