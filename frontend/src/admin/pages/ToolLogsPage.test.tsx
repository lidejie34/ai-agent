import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import dayjs from 'dayjs'
import ToolLogsPage, { buildLogQuery } from './ToolLogsPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { PageResult, ToolCallLog } from '../types'

// T5：审计日志页（AC-38~45、D8）

const logs: ToolCallLog[] = [
  {
    id: 1, callId: 'call-1', toolName: 'weather_query', handlerType: 'BUILTIN',
    sessionId: 'sess-abc', inputSummary: 'city=北京', status: 'SUCCESS',
    durationMs: 1234, resultChars: 500, createdAt: '2026-09-01 10:00:00',
  },
  {
    id: 2, callId: 'call-2', toolName: 'script_tool', handlerType: 'SCRIPT',
    status: 'FAILED', durationMs: null, resultChars: null,
    errorMessage: '脚本执行失败：文件不存在', inputSummary: 'q=x',
    createdAt: '2026-09-01 10:05:00',
  },
  {
    id: 3, callId: 'call-3', toolName: 'mcp_search', handlerType: 'MCP',
    status: 'TIMEOUT', durationMs: 60000, resultChars: 0,
    createdAt: '2026-09-01 10:10:00',
  },
]

const page = (content: ToolCallLog[], total = 55): PageResult<ToolCallLog> => ({
  content, total, page: 0, size: 20,
})

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x')
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  message.destroy()
  document.querySelectorAll('.ant-modal-root').forEach((n) => n.remove())
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const logUrls = (fn: ReturnType<typeof vi.fn>) =>
  fn.mock.calls.filter((c) => String(c[0]).includes('/api/admin/tool-call-logs')).map((c) => String(c[0]))

// antd Select 关闭后下拉容器在 jsdom 中离场残留（pointer-events:none），
// 只在可见下拉 :not(.ant-select-dropdown-hidden) 内查找选项
async function visibleDropdown() {
  return waitFor(() => {
    // 离场动画中的旧下拉既无 -hidden 类也不可操作（class 含 leave），需一并排除
    const d = document.querySelector(
      '.ant-select-dropdown:not(.ant-select-dropdown-hidden):not([class*="leave"])',
    )
    expect(d).toBeTruthy()
    return d as HTMLElement
  })
}

async function selectOption(placeholder: string, optionName: string) {
  // placeholder 节点自身 pointer-events:none，点击其 selector 容器
  const target = screen.getByText(placeholder).closest('.ant-select-selector') as HTMLElement
  await userEvent.click(target)
  const dropdown = await visibleDropdown()
  const opt = Array.from(dropdown.querySelectorAll('.ant-select-item-option')).find(
    (el) => el.textContent === optionName,
  )
  await userEvent.click(opt as HTMLElement)
}

describe('审计日志页', () => {
  it('首屏请求 page=0&size=20；列渲染：时间/工具名/类型状态 Tag/会话/耗时格式化/结果字符数（AC-38）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)

    await waitFor(() => expect(logUrls(fn)).toHaveLength(1))
    expect(logUrls(fn)[0]).toBe('/api/admin/tool-call-logs?page=0&size=20')

    expect(await screen.findByText('2026-09-01 10:00:00')).toBeInTheDocument()
    expect(screen.getByText('weather_query')).toBeInTheDocument()
    expect(screen.getByText('BUILTIN')).toBeInTheDocument()
    expect(screen.getByText('SUCCESS')).toBeInTheDocument()
    expect(screen.getByText('SCRIPT')).toBeInTheDocument()
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('MCP')).toBeInTheDocument()
    expect(screen.getByText('TIMEOUT')).toBeInTheDocument()
    expect(screen.getByText('sess-abc')).toBeInTheDocument()
    expect(screen.getByText('1.23 s')).toBeInTheDocument() // 1234ms
    expect(screen.getByText('60.00 s')).toBeInTheDocument() // 60000ms
    expect(screen.getByText('500')).toBeInTheDocument()
  })

  it('分页：0 基对齐后端——点第 2 页发 page=1；切每页 50 条发 size=50&page=0（AC-39/D8）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await waitFor(() => expect(logUrls(fn)).toHaveLength(1))

    await userEvent.click(document.querySelector('.ant-pagination-item-2') as HTMLElement)
    await waitFor(() => expect(logUrls(fn).some((u) => u.includes('page=1') && u.includes('size=20'))).toBe(true))

    // 每页条数：10/20/50，选最后一项（50）
    await userEvent.click(document.querySelector('.ant-pagination-options .ant-select-selector') as HTMLElement)
    const dropdown = await visibleDropdown()
    const sizeOptions = dropdown.querySelectorAll('.ant-select-item-option')
    await userEvent.click(sizeOptions[sizeOptions.length - 1] as HTMLElement)
    await waitFor(() => expect(logUrls(fn).some((u) => u.includes('size=50') && u.includes('page=0'))).toBe(true))
  })

  it('文本过滤：工具名/会话 ID 查询带参且重置到第 0 页；翻页保持过滤条件（AC-40）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await screen.findByText('weather_query')

    await userEvent.type(screen.getByPlaceholderText('工具名'), 'weather')
    await userEvent.type(screen.getByPlaceholderText('会话 ID'), 'sess-1')
    await userEvent.click(screen.getByRole('button', { name: /^查\s*询$/ }))

    await waitFor(() => {
      const last = logUrls(fn)[logUrls(fn).length - 1]
      expect(last).toContain('toolName=weather')
      expect(last).toContain('sessionId=sess-1')
      expect(last).toContain('page=0')
    })

    // 翻页保持过滤
    await userEvent.click(document.querySelector('.ant-pagination-item-2') as HTMLElement)
    await waitFor(() => {
      const last = logUrls(fn)[logUrls(fn).length - 1]
      expect(last).toContain('toolName=weather')
      expect(last).toContain('page=1')
    })
  })

  it('下拉过滤：status=FAILED、handlerType=MCP 查询入参（AC-40）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await screen.findByText('weather_query')

    await selectOption('全部状态', '失败')
    await selectOption('全部类型', 'MCP')
    await userEvent.click(screen.getByRole('button', { name: /^查\s*询$/ }))

    await waitFor(() => {
      const last = logUrls(fn)[logUrls(fn).length - 1]
      expect(last).toContain('status=FAILED')
      expect(last).toContain('handlerType=MCP')
      expect(last).toContain('page=0')
    })
  })

  it('时间范围：buildLogQuery 输出 yyyy-MM-dd HH:mm:ss，时分秒默认 00:00:00/23:59:59（AC-40/D8）', () => {
    const q = buildLogQuery(
      { range: [dayjs('2026-09-01 00:00:00'), dayjs('2026-09-01 23:59:59')] },
      0,
      20,
    )
    expect(q.from).toBe('2026-09-01 00:00:00')
    expect(q.to).toBe('2026-09-01 23:59:59')

    const empty = buildLogQuery({ toolName: '   ' }, 2, 50)
    expect(empty).toEqual({ page: 2, size: 50 })
  })

  it('重置：清空过滤并回到 page=0&size=20 无过滤参数（AC-40）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await screen.findByText('weather_query')

    await userEvent.type(screen.getByPlaceholderText('工具名'), 'weather')
    await userEvent.click(screen.getByRole('button', { name: /^查\s*询$/ }))
    await waitFor(() => expect(logUrls(fn).some((u) => u.includes('toolName=weather'))).toBe(true))

    await userEvent.click(screen.getByRole('button', { name: /^重\s*置$/ }))
    await waitFor(() => {
      const last = logUrls(fn)[logUrls(fn).length - 1]
      expect(last).toBe('/api/admin/tool-call-logs?page=0&size=20')
    })
  })

  it('行展开：FAILED 显示 errorMessage；TIMEOUT 无错误信息兜底；SUCCESS 展开显示输入摘要（AC-41）', async () => {
    stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await screen.findByText('weather_query')

    // 第 2 行 FAILED
    const icons = document.querySelectorAll('.ant-table-row-expand-icon')
    await userEvent.click(icons[1])
    expect(await screen.findByText('脚本执行失败：文件不存在')).toBeInTheDocument()

    // 第 3 行 TIMEOUT，无 errorMessage → 兜底
    await userEvent.click(icons[2])
    const expandedRows = document.querySelectorAll('.ant-table-expanded-row')
    expect(Array.from(expandedRows).some((r) => r.textContent?.includes('无错误信息'))).toBe(true)

    // 第 1 行 SUCCESS → 输入摘要
    await userEvent.click(icons[0])
    await waitFor(() => {
      const rows = document.querySelectorAll('.ant-table-expanded-row')
      expect(Array.from(rows).some((r) => r.textContent?.includes('city=北京'))).toBe(true)
    })
  })

  it('缺省兜底：sessionId/durationMs/resultChars 缺省显示「-」（AC-42/D9）', async () => {
    stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await screen.findByText('weather_query')
    // 第 2、3 行无 sessionId；第 2 行 durationMs/resultChars 为 null
    const dashes = document.querySelectorAll('.admin-text-empty')
    expect(dashes.length).toBeGreaterThanOrEqual(3)
  })

  it('空数据：Empty 文案「无符合条件的日志」（AC-43）', async () => {
    stubFetch(() => jsonResponse(page([], 0)))
    render(<ToolLogsPage />)
    expect(await screen.findByText('无符合条件的日志')).toBeInTheDocument()
  })

  it('400 BAD_REQUEST：后端校验 message 原文上屏（AC-44）', async () => {
    stubFetch(() => apiErrorResponse('BAD_REQUEST', 'from/to 时间格式不正确', 400))
    render(<ToolLogsPage />)
    expect(await screen.findByText('from/to 时间格式不正确')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^重\s*试$/ })).toBeInTheDocument()
  })

  it('手动刷新按钮重拉当前参数；无轮询（等待后请求数不增加）（AC-45）', async () => {
    const fn = stubFetch(() => jsonResponse(page(logs)))
    render(<ToolLogsPage />)
    await waitFor(() => expect(logUrls(fn)).toHaveLength(1))

    await userEvent.click(screen.getByTestId('logs-refresh'))
    await waitFor(() => expect(logUrls(fn)).toHaveLength(2))
    expect(logUrls(fn)[1]).toBe('/api/admin/tool-call-logs?page=0&size=20')

    // 无轮询：700ms 内不再自发请求
    const countAfterWait = await new Promise<number>((resolve) =>
      setTimeout(() => resolve(logUrls(fn).length), 700),
    )
    expect(countAfterWait).toBe(2)
  })
})
