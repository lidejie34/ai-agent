import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import AdminConsole from '../AdminConsole'
import { clearAdminToken } from '../auth'
import { listTools } from '../api/adminApi'
import { apiErrorResponse, jsonResponse } from '../testHelpers'

// T1：登录页 + 控制台壳（AC-2/6/7/8/9/10/11/12/47）
const KEY = 'admin.token'

beforeEach(() => {
  window.location.hash = ''
  clearAdminToken()
  localStorage.removeItem(KEY)
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  localStorage.removeItem(KEY)
  window.location.hash = ''
  message.destroy()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

function toolsOk() {
  return stubFetch((url) => {
    if (url.includes('/api/admin/tools')) return jsonResponse([])
    return new Response('not found', { status: 404 })
  })
}

function renderConsole(hash = '#/admin') {
  window.location.hash = hash
  return render(<AdminConsole />)
}

async function loginWith(token: string) {
  await userEvent.type(screen.getByTestId('admin-token-input'), token)
  await userEvent.click(screen.getByTestId('admin-login-btn'))
}

describe('登录页 / 控制台壳', () => {
  it('未登录渲染登录卡片；空 token 登录按钮禁用；渲染阶段零 admin 请求（AC-6）', () => {
    const fn = toolsOk()
    renderConsole()
    expect(screen.getByTestId('admin-token-input')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入管理令牌')).toBeInTheDocument()
    expect(screen.getByTestId('admin-login-btn')).toBeDisabled()
    expect(fn).not.toHaveBeenCalled()
  })

  it('输入 token 后登录按钮可提交（AC-6）', async () => {
    toolsOk()
    renderConsole()
    const input = screen.getByTestId('admin-token-input')
    await userEvent.type(input, 'x')
    expect(screen.getByTestId('admin-login-btn')).toBeEnabled()
  })

  it('登录成功：发 GET /api/admin/tools 且带头，200 写盘 admin.token 并进入工具页（AC-7）', async () => {
    const fn = toolsOk()
    renderConsole()
    await loginWith('good-token')
    await waitFor(() => expect(localStorage.getItem(KEY)).toBe('good-token'))
    const verifyCall = fn.mock.calls.find(([u]) => String(u).includes('/api/admin/tools'))!
    expect(verifyCall).toBeTruthy()
    expect((verifyCall[1] as RequestInit | undefined)?.method ?? 'GET').toBe('GET')
    const headers = ((verifyCall[1] as RequestInit | undefined)?.headers ?? {}) as Record<string, string>
    expect(headers['X-Admin-Token']).toBe('good-token')
    expect(await screen.findByTestId('admin-layout')).toBeInTheDocument()
    expect(await screen.findByTestId('admin-page-tools')).toBeInTheDocument()
  })

  it('401：提示「管理令牌无效」、不写盘、停留登录页且保留输入；不弹「登录已失效」（AC-8）', async () => {
    stubFetch(() => apiErrorResponse('ADMIN_UNAUTHORIZED', 'token mismatch', 401))
    renderConsole()
    await loginWith('bad-token')
    expect(await screen.findByText('管理令牌无效，请重新输入')).toBeInTheDocument()
    expect(localStorage.getItem(KEY)).toBeNull()
    expect(screen.getByTestId('admin-token-input')).toHaveValue('bad-token')
    expect(screen.queryByTestId('admin-layout')).toBeNull()
    expect(screen.queryByText('登录已失效，请重新登录')).toBeNull()
  })

  it('503 ADMIN_NOT_CONFIGURED：提示联系运维配置（AC-9）', async () => {
    stubFetch(() => apiErrorResponse('ADMIN_NOT_CONFIGURED', 'not configured', 503))
    renderConsole()
    await loginWith('any-token')
    expect(
      await screen.findByText('服务端未配置管理令牌，请联系运维配置 APP_ADMIN_TOKEN'),
    ).toBeInTheDocument()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('400 TOOLS_DISABLED：提示功能未启用，文案不声称令牌错误（AC-10）', async () => {
    stubFetch(() => apiErrorResponse('TOOLS_DISABLED', 'tools disabled', 400))
    renderConsole()
    await loginWith('any-token')
    const alert = await screen.findByText(/工具功能未启用/)
    expect(alert).toBeInTheDocument()
    expect(alert.textContent).toContain('管理控制台暂不可用')
    expect(screen.queryByText(/令牌无效/)).toBeNull()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('已登录后任一 admin 请求 401 → 清 token、回登录页、提示「登录已失效」；连续 401 只提示一次（AC-11/47）', async () => {
    let calls = 0
    stubFetch(() => {
      calls += 1
      // 前两次 200：登录验证 GET + 登录成功后工具页挂载自动加载；此后一律 401
      return calls <= 2 ? jsonResponse([]) : apiErrorResponse('ADMIN_UNAUTHORIZED', 'expired', 401)
    })
    renderConsole()
    await loginWith('good-token')
    await screen.findByTestId('admin-layout')
    expect(localStorage.getItem(KEY)).toBe('good-token')

    // 会话中任意 admin 请求 401（真实链路：adminFetch → notifyUnauthorized）
    await expect(listTools()).rejects.toMatchObject({ code: 'ADMIN_UNAUTHORIZED' })
    expect(await screen.findByText('登录已失效，请重新登录')).toBeInTheDocument()
    expect(await screen.findByTestId('admin-token-input')).toBeInTheDocument()
    expect(localStorage.getItem(KEY)).toBeNull()

    // 登出后再到达一个 401：不重复提示（ref 去重）
    const noticeCount = screen.queryAllByText('登录已失效，请重新登录').length
    await expect(listTools()).rejects.toMatchObject({ code: 'ADMIN_UNAUTHORIZED' })
    await waitFor(() => {
      expect(screen.queryAllByText('登录已失效，请重新登录')).toHaveLength(noticeCount)
    })
  })

  it('退出登录：Popconfirm 确认后清 token、回登录页；后续请求不带头（AC-12）', async () => {
    const fn = toolsOk()
    renderConsole()
    await loginWith('good-token')
    await screen.findByTestId('admin-layout')

    await userEvent.click(screen.getByTestId('admin-logout-btn'))
    // antd 双字中文按钮自动插空格（「退 出」），用精确正则
    const okBtn = await screen.findByRole('button', { name: /^退\s*出$/ })
    await userEvent.click(okBtn)

    expect(await screen.findByTestId('admin-token-input')).toBeInTheDocument()
    expect(localStorage.getItem(KEY)).toBeNull()

    // 退出后 admin 请求不再携带 X-Admin-Token
    await listTools()
    const last = fn.mock.calls[fn.mock.calls.length - 1]
    const headers = ((last[1] as RequestInit | undefined)?.headers ?? {}) as Record<string, string>
    expect(headers['X-Admin-Token']).toBeUndefined()
  })

  it('直达 #/admin/mcp 登录成功后落在目标子页（AC-2）', async () => {
    toolsOk()
    renderConsole('#/admin/mcp')
    await loginWith('good-token')
    await screen.findByTestId('admin-layout')
    expect(screen.getByTestId('admin-page-mcp')).toBeInTheDocument()
    expect(screen.queryByTestId('admin-page-tools')).toBeNull()
  })

  it('Tabs 切换联动 hash 与子页（AC-4）', async () => {
    toolsOk()
    renderConsole()
    await loginWith('good-token')
    await screen.findByTestId('admin-page-tools')
    await userEvent.click(screen.getByRole('tab', { name: '审计日志' }))
    await waitFor(() => expect(window.location.hash).toBe('#/admin/logs'))
    expect(await screen.findByTestId('admin-page-logs')).toBeInTheDocument()
  })
})
