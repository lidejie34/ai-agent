import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import ToolsPage from './ToolsPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { ToolDetail, ToolListItem } from '../types'

// T3：工具注册表列表/启停/详情/删除（AC-21~26、AC-36/37）
const tools: ToolListItem[] = [
  {
    id: 1,
    name: 'weather_query',
    description: '查询天气',
    handlerType: 'BUILTIN',
    enabled: true,
    timeoutMs: 5000,
    outputMaxChars: 4000,
    guideLength: 120,
    createdAt: '2026-09-01 10:00:00',
    updatedAt: '2026-09-02 11:00:00',
  },
  {
    id: 2,
    name: 'long_script_tool',
    description: '超长描述'.repeat(100),
    handlerType: 'SCRIPT',
    enabled: false,
    timeoutMs: null,
    outputMaxChars: null,
    guideLength: 0,
    createdAt: '2026-09-03 10:00:00',
    updatedAt: '2026-09-04 12:00:00',
  },
]

const detail1: ToolDetail = {
  id: 1,
  name: 'weather_query',
  description: '查询天气',
  inputSchema: { type: 'object', properties: { city: { type: 'string' } } },
  handlerType: 'BUILTIN',
  handlerConfig: { bean: 'weatherTool' },
  guideMd: '# 使用指南\n调用时提供城市名',
  enabled: true,
  timeoutMs: 5000,
  outputMaxChars: 4000,
  createdAt: '2026-09-01 10:00:00',
  updatedAt: '2026-09-02 11:00:00',
}

const detail2: ToolDetail = {
  id: 2,
  name: 'long_script_tool',
  description: '脚本工具',
  inputSchema: null,
  handlerType: 'SCRIPT',
  handlerConfig: null,
  guideMd: '',
  enabled: false,
  timeoutMs: null,
  outputMaxChars: null,
  createdAt: '2026-09-03 10:00:00',
  updatedAt: '2026-09-04 12:00:00',
}

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x')
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  message.destroy()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

// 列表 GET /api/admin/tools（精确，不含 /tools/{id}）
const listCalls = (fn: ReturnType<typeof vi.fn>) =>
  fn.mock.calls.filter((c) => c[0] === '/api/admin/tools' && ((c[1] as RequestInit | undefined)?.method ?? 'GET') === 'GET')

describe('工具注册表', () => {
  it('列表渲染全部行（含停用行）：Tag/Switch/指南字数/默认值提示/时间格式，id 升序（AC-21）', async () => {
    stubFetch(() => jsonResponse(tools))
    render(<ToolsPage />)
    expect(await screen.findByText('weather_query')).toBeInTheDocument()
    expect(screen.getByText('long_script_tool')).toBeInTheDocument()

    const switches = screen.getAllByRole('switch')
    expect(switches[0]).toBeChecked()
    expect(switches[1]).not.toBeChecked()

    expect(screen.getByText('无指南')).toBeInTheDocument()
    expect(screen.getByText('指南 120 字')).toBeInTheDocument()
    expect(screen.getAllByText('默认 30000').length).toBeGreaterThan(0)
    expect(screen.getAllByText('默认 8000').length).toBeGreaterThan(0)
    expect(screen.getByText('2026-09-02 11:00:00')).toBeInTheDocument()
    // handlerType Tag
    expect(screen.getByText('BUILTIN')).toBeInTheDocument()
    expect(screen.getByText('SCRIPT')).toBeInTheDocument()
  })

  it('描述超长省略：单元格保留全文（CSS 省略 + Tooltip 包裹）（AC-21）', async () => {
    stubFetch(() => jsonResponse(tools))
    render(<ToolsPage />)
    await screen.findByText('weather_query')
    const cell = screen.getByText('超长描述'.repeat(100))
    expect(cell.closest('.admin-cell-ellipsis')).toBeTruthy()
  })

  it('Switch 切换发 PATCH {"enabled":x} 且带头；请求中控件 loading；成功提示并保持新值（AC-22）', async () => {
    const def = deferred<Response>()
    const fn = stubFetch((url, init) => {
      if ((init?.method ?? 'GET') === 'PATCH') return def.promise
      if (url.includes('/api/admin/tools')) return jsonResponse(tools)
      return new Response('nf', { status: 404 })
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')

    await userEvent.click(screen.getAllByRole('switch')[1]) // 停用行 → 启用
    await waitFor(() =>
      expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'PATCH')).toBe(true),
    )
    const patchCall = fn.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'PATCH')!
    expect(patchCall[0]).toContain('/api/admin/tools/2')
    expect(JSON.parse((patchCall[1] as RequestInit).body as string)).toEqual({ enabled: true })
    expect(((patchCall[1] as RequestInit).headers as Record<string, string>)['X-Admin-Token']).toBe('tok-x')
    // 请求中 Switch loading 防重复
    expect(document.querySelector('.ant-switch-loading')).toBeTruthy()

    def.resolve(jsonResponse({ ...tools[1], enabled: true }))
    expect(await screen.findByText('工具已启用')).toBeInTheDocument()
    expect(document.querySelector('.ant-switch-loading')).toBeNull()
    expect(screen.getAllByRole('switch')[1]).toBeChecked()
  })

  it('PATCH 失败：回滚原值 + 错误文案，不整表刷新（AC-22/46）', async () => {
    const fn = stubFetch((url, init) => {
      if ((init?.method ?? 'GET') === 'PATCH') return apiErrorResponse('TOOLS_UNAVAILABLE', 'refresh fail', 503)
      if (url.includes('/api/admin/tools')) return jsonResponse(tools)
      return new Response('nf', { status: 404 })
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')
    expect(listCalls(fn)).toHaveLength(1)

    await userEvent.click(screen.getAllByRole('switch')[1])
    expect(await screen.findByText('工具服务暂不可用（数据库访问失败），请稍后重试')).toBeInTheDocument()
    expect(screen.getAllByRole('switch')[1]).not.toBeChecked() // 回滚
    expect(listCalls(fn)).toHaveLength(1) // 不整表重拉
  })

  it('详情抽屉：发 GET /tools/{id}，全字段只读、JSON 缩进、null 兜底「-」、空指南「无指南」（AC-25）', async () => {
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET' && /\/api\/admin\/tools\/\d+/.test(url)) {
        const id = Number(url.match(/\/tools\/(\d+)/)![1])
        return jsonResponse(id === 1 ? detail1 : detail2)
      }
      return jsonResponse(tools)
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')

    // 第一行详情
    await userEvent.click(screen.getAllByRole('button', { name: /^详\s*情$/ })[0])
    expect(await screen.findByText(/处理配置/)).toBeInTheDocument()
    expect(screen.getByText(/"bean": "weatherTool"/)).toBeInTheDocument()
    expect(screen.getByText(/"properties"/)).toBeInTheDocument()
    expect(screen.getByText(/调用时提供城市名/)).toBeInTheDocument()
    expect(screen.getByText('已启用')).toBeInTheDocument()

    // 关闭后开第二行：null schema/config → 「-」，空 guideMd → 「无指南」
    await userEvent.click(document.querySelector('.ant-drawer-close') as HTMLElement)
    await waitFor(() => expect(screen.queryByText(/调用时提供城市名/)).toBeNull())
    await userEvent.click(screen.getAllByRole('button', { name: /^详\s*情$/ })[1])
    // 表格已有 1 个「无指南」Tag，抽屉空指南再出现 1 个
    await waitFor(() => expect(screen.getAllByText('无指南').length).toBeGreaterThanOrEqual(2))
    expect(screen.getAllByText('-').length).toBeGreaterThanOrEqual(2)
  })

  it('详情 404：提示「工具不存在或已被删除」、关抽屉、列表刷新（AC-26）', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET' && /\/api\/admin\/tools\/\d+/.test(url)) {
        return apiErrorResponse('TOOL_NOT_FOUND', 'not found', 404)
      }
      return jsonResponse(tools)
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')
    await userEvent.click(screen.getAllByRole('button', { name: /^详\s*情$/ })[0])

    expect(await screen.findByText('工具不存在或已被删除')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText(/处理配置/)).toBeNull())
    expect(listCalls(fn)).toHaveLength(2) // 自动刷新列表
  })

  it('删除 Popconfirm：文案含工具名/无法调用/审计保留/不可恢复；确认发 DELETE、防重、成功刷新（AC-36/37）', async () => {
    const def = deferred<Response>()
    const fn = stubFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'DELETE') return def.promise
      return jsonResponse(tools)
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')

    await userEvent.click(screen.getAllByRole('button', { name: /^删\s*除$/ })[0])
    const pop = (await waitFor(() => document.querySelector('.ant-popconfirm'))) as HTMLElement
    expect(pop.textContent).toContain('weather_query')
    expect(pop.textContent).toContain('模型无法再调用')
    expect(pop.textContent).toContain('历史审计日志保留')
    expect(pop.textContent).toContain('不可恢复')

    await userEvent.click(pop.querySelector('.ant-btn-dangerous') as HTMLElement)
    await waitFor(() =>
      expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'DELETE')).toBe(true),
    )
    const delCall = fn.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'DELETE')!
    expect(delCall[0]).toContain('/api/admin/tools/1')
    expect(((delCall[1] as RequestInit).headers as Record<string, string>)['X-Admin-Token']).toBe('tok-x')
    expect(pop.querySelector('.ant-btn-loading')).toBeTruthy() // 防重复

    def.resolve(jsonResponse(detail1))
    expect(await screen.findByText('工具已删除')).toBeInTheDocument()
    await waitFor(() => expect(listCalls(fn)).toHaveLength(2))
  })

  it('删除 404：提示「工具已被删除」并刷新列表（AC-37）', async () => {
    const fn = stubFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'DELETE') return apiErrorResponse('TOOL_NOT_FOUND', 'gone', 404)
      return jsonResponse(tools)
    })
    render(<ToolsPage />)
    await screen.findByText('weather_query')

    await userEvent.click(screen.getAllByRole('button', { name: /^删\s*除$/ })[0])
    const pop = (await waitFor(() => document.querySelector('.ant-popconfirm'))) as HTMLElement
    await userEvent.click(pop.querySelector('.ant-btn-dangerous') as HTMLElement)

    expect(await screen.findByText('工具已被删除')).toBeInTheDocument()
    await waitFor(() => expect(listCalls(fn)).toHaveLength(2))
  })

  it('「新建工具」主按钮存在（AC-24）', async () => {
    stubFetch(() => jsonResponse(tools))
    render(<ToolsPage />)
    await screen.findByText('weather_query')
    expect(screen.getByRole('button', { name: /新建工具/ })).toBeInTheDocument()
  })

  it('加载失败 Alert + 差异化文案 + 重试；刷新按钮手动重拉（AC-23）', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(apiErrorResponse('TOOLS_UNAVAILABLE', 'db', 503))
      .mockResolvedValueOnce(jsonResponse(tools))
      .mockResolvedValueOnce(jsonResponse(tools))
    vi.stubGlobal('fetch', fn)
    render(<ToolsPage />)

    expect(await screen.findByText('工具服务暂不可用（数据库访问失败），请稍后重试')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^重\s*试$/ }))
    expect(await screen.findByText('weather_query')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('tools-refresh'))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(3))
  })
})
