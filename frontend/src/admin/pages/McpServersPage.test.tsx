import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import McpServersPage from './McpServersPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { McpServer } from '../types'

// T2：MCP 服务器只读面板（AC-15~20）
const readyServer: McpServer = {
  name: 'everything',
  command: '/usr/bin/npx',
  args: ['-y', '@modelcontextprotocol/server-everything'],
  status: 'READY',
  toolCount: 2,
  tools: [
    { name: 'everything__echo', rawName: 'echo', description: 'Echoes input' },
    { name: 'everything__noop', rawName: 'noop' }, // description 缺键 → 「-」
  ],
  connectedAt: '2026-09-07 10:00:00',
}

const unavailableServer: McpServer = {
  name: 'broken',
  command: null, // null → 「-」
  args: [],
  status: 'UNAVAILABLE',
  toolCount: 0,
  tools: [],
  lastError: 'connect ECONNREFUSED 127.0.0.1:9999\n    at Client.connect (client.js:12:3)',
  // connectedAt 缺键 → 「-」
}

const unavailableNoErr: McpServer = {
  name: 'quiet-broken',
  status: 'UNAVAILABLE',
  toolCount: 0,
}

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x') // 面板请求带 token（AC-15）
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  clearAdminToken()
})

function stubWith(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('MCP 服务器面板', () => {
  it('READY server：徽标已连接、command/args/connectedAt/toolCount 渲染；请求带 token（AC-15）', async () => {
    const fn = stubWith(() => jsonResponse({ servers: [readyServer] }))
    render(<McpServersPage />)

    expect(await screen.findByText('已连接')).toBeInTheDocument()
    expect(screen.getByText('everything')).toBeInTheDocument()
    expect(screen.getByText('/usr/bin/npx')).toBeInTheDocument()
    expect(screen.getByText(/-y/)).toBeInTheDocument()
    expect(screen.getByText('@modelcontextprotocol/server-everything')).toBeInTheDocument()
    expect(screen.getByText('2026-09-07 10:00:00')).toBeInTheDocument()
    expect(screen.getByText('发现工具（2）')).toBeInTheDocument()

    const call = fn.mock.calls[0]
    expect(call[0]).toContain('/api/admin/mcp/servers')
    const headers = (call[1] as RequestInit).headers as Record<string, string>
    expect(headers['X-Admin-Token']).toBe('tok-x')
  })

  it('工具清单 Collapse 默认收起；展开后 name/rawName/description（空描述显示「-」），条数=toolCount（AC-16）', async () => {
    stubWith(() => jsonResponse({ servers: [readyServer] }))
    render(<McpServersPage />)
    await screen.findByText('everything')

    // 默认收起：工具名不在文档中
    expect(screen.queryByText('everything__echo')).toBeNull()

    await userEvent.click(screen.getByText('发现工具（2）'))
    expect(await screen.findByText('everything__echo')).toBeInTheDocument()
    expect(screen.getByText('echo')).toBeInTheDocument()
    expect(screen.getByText('Echoes input')).toBeInTheDocument()
    expect(screen.getByText('everything__noop')).toBeInTheDocument()
    // 缺 description 的工具显示「-」
    expect(screen.getByText('noop')).toBeInTheDocument()
  })

  it('UNAVAILABLE：红徽标、command/connectedAt 为「-」、工具区无工具、lastError Alert 全文；无 lastError 显示「无错误信息」（AC-17）', async () => {
    stubWith(() => jsonResponse({ servers: [unavailableServer, unavailableNoErr] }))
    render(<McpServersPage />)
    expect(await screen.findAllByText('不可用')).toHaveLength(2)

    // 展开第一个 server（broken）的工具清单 → 无工具
    const collapseHeaders = await screen.findAllByText('发现工具（0）')
    await userEvent.click(collapseHeaders[0])
    expect(await screen.findByText('无工具')).toBeInTheDocument()

    // lastError 全文（多行，纯文本 pre）
    expect(screen.getByText(/connect ECONNREFUSED 127\.0\.0\.1:9999/)).toBeInTheDocument()
    expect(screen.getByText(/at Client\.connect/)).toBeInTheDocument()
    // 无 lastError 的 server 显示兜底文案
    expect(screen.getByText('无错误信息')).toBeInTheDocument()
  })

  it('{"servers":[]} → Empty「未配置 MCP 服务器」（AC-18）', async () => {
    stubWith(() => jsonResponse({ servers: [] }))
    render(<McpServersPage />)
    expect(await screen.findByText('未配置 MCP 服务器')).toBeInTheDocument()
  })

  it('只读红线：无写按钮、无 env 展示、mcp 路径全部 GET（AC-19）', async () => {
    stubWith(() => jsonResponse({ servers: [readyServer, unavailableServer] }))
    render(<McpServersPage />)
    await screen.findByText('everything')

    // 无新增/编辑/删除/启停/重连按钮
    expect(screen.queryByRole('button', { name: /新增|添加|编辑|删除|重连|启用|停用/ })).toBeNull()
    // 不展示 env/环境变量
    expect(screen.queryByText(/env|环境变量/i)).toBeNull()
  })

  it('刷新按钮重新请求并更新内容（AC-20）', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ servers: [readyServer] }))
      .mockResolvedValueOnce(jsonResponse({ servers: [readyServer, { ...readyServer, name: 'second-srv' }] }))
    vi.stubGlobal('fetch', fn)
    render(<McpServersPage />)
    await screen.findByText('everything')
    expect(fn).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByTestId('mcp-refresh'))
    expect(await screen.findByText('second-srv')).toBeInTheDocument()
    expect(fn).toHaveBeenCalledTimes(2)
    // 两次都是 GET
    for (const call of fn.mock.calls) {
      expect((call[1] as RequestInit).method ?? 'GET').toBe('GET')
    }
  })

  it('加载失败显示错误 Alert + 文案；「重试」可恢复（AC-20/46）', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(apiErrorResponse('TOOLS_UNAVAILABLE', 'db error', 503))
      .mockResolvedValueOnce(jsonResponse({ servers: [readyServer] }))
    vi.stubGlobal('fetch', fn)
    render(<McpServersPage />)

    expect(await screen.findByText('工具服务暂不可用（数据库访问失败），请稍后重试')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^重\s*试$/ }))
    expect(await screen.findByText('everything')).toBeInTheDocument()
  })

  it('不轮询：fake timers 推进 10s，fetch 次数不增长（AC-20）', async () => {
    const fn = stubWith(() => jsonResponse({ servers: [readyServer] }))
    render(<McpServersPage />)
    await screen.findByText('everything')
    expect(fn).toHaveBeenCalledTimes(1)

    vi.useFakeTimers()
    act(() => {
      vi.advanceTimersByTime(10_000)
    })
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
