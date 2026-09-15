import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import DimPage from './DimPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { DimProject, DimTagView } from '../types'

// 迭代10 追加：维度维护页——项目 CRUD（重名 409/引用中禁删 409 透传/改名联动提示）、
// 标签派生列表（改名/删除联动文档数展示）、加载失败重试。

const project = (over: Partial<DimProject> = {}): DimProject => ({
  id: 1,
  name: '订单域',
  remark: '订单相关制度',
  docCount: 3,
  createdAt: '2026-09-15 10:00:00',
  updatedAt: '2026-09-15 10:05:00',
  ...over,
})

const tag = (over: Partial<DimTagView> = {}): DimTagView => ({ name: '售后', docCount: 2, ...over })

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x')
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  // jsdom 无 CSS 过渡，message.destroy() 无法移除节点——同文案 toast 跨用例残留，
  // 断言一律 findAllByText 兜底 + 请求次数保证严格性
  message.destroy()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

/** 默认路由：projects/tags 给定列表；其余 404。 */
function stubDefault(projects: DimProject[], tags: DimTagView[], extra?: Record<string, Response>) {
  return stubFetch((url, init) => {
    const method = init?.method ?? 'GET'
    if (url.endsWith('/api/admin/dim/projects') && method === 'GET') return jsonResponse(projects)
    if (url.endsWith('/api/admin/dim/tags') && method === 'GET') return jsonResponse(tags)
    if (extra) {
      const hit = Object.keys(extra).find((k) => url.includes(k))
      if (hit) return extra[hit]
    }
    return new Response('not found', { status: 404 })
  })
}

/** 弹窗底部主按钮（保存）。 */
function modalOk(): HTMLElement {
  return document.querySelector('.ant-modal-footer .ant-btn-primary') as HTMLElement
}

describe('维度维护页：项目', () => {
  it('进页拉 projects+tags；表格渲染名称/备注/文档数/时间', async () => {
    stubDefault([project()], [tag()])
    render(<DimPage />)

    await screen.findByText('订单域')
    expect(screen.getByText('订单相关制度')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('2026-09-15 10:05:00')).toBeInTheDocument()
    expect(screen.getByText('售后')).toBeInTheDocument()
  })

  it('新建项目：POST body {name, remark} → 成功提示并刷新', async () => {
    const fn = stubDefault([], [], {
      '/api/admin/dim/projects': jsonResponse(project({ id: 7 }), 201),
    })
    render(<DimPage />)
    await screen.findByTestId('dim-project-create')

    await userEvent.click(screen.getByTestId('dim-project-create'))
    fireEvent.change(await screen.findByTestId('dim-project-name'), { target: { value: ' 订单域 ' } })
    fireEvent.change(screen.getByTestId('dim-project-remark'), { target: { value: '订单制度' } })
    await userEvent.click(modalOk())

    await waitFor(() => {
      const posts = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'POST')
      expect(posts).toHaveLength(1)
      expect(JSON.parse(posts[0][1]?.body as string)).toEqual({ name: '订单域', remark: '订单制度' })
    })
    expect(await screen.findAllByText(/项目「订单域」已创建/)).not.toHaveLength(0)
  })

  it('新建重名 409 KB_PROJECT_EXISTS：透传后端 message', async () => {
    stubDefault([], [], {
      '/api/admin/dim/projects': apiErrorResponse('KB_PROJECT_EXISTS', '项目已存在：订单域', 409),
    })
    render(<DimPage />)
    await screen.findByTestId('dim-project-create')

    await userEvent.click(screen.getByTestId('dim-project-create'))
    fireEvent.change(await screen.findByTestId('dim-project-name'), { target: { value: '订单域' } })
    await userEvent.click(modalOk())

    expect(await screen.findAllByText(/项目已存在：订单域/)).not.toHaveLength(0)
  })

  it('新建非法名（含逗号）：前端预检拦截，不发 POST', async () => {
    const fn = stubDefault([], [])
    render(<DimPage />)
    await screen.findByTestId('dim-project-create')

    await userEvent.click(screen.getByTestId('dim-project-create'))
    fireEvent.change(await screen.findByTestId('dim-project-name'), { target: { value: '含,逗号' } })
    await userEvent.click(modalOk())

    expect(await screen.findAllByText(/项目名仅支持中文\/字母\/数字\/中划线\/下划线/)).not.toHaveLength(0)
    expect(fn.mock.calls.filter((c) => (c[1] as RequestInit)?.method === 'POST')).toHaveLength(0)
  })

  it('编辑改名：PATCH {name, remark} → 提示联动更新', async () => {
    const fn = stubDefault([project()], [], {
      '/api/admin/dim/projects/1': jsonResponse(project({ name: '交易域' })),
    })
    render(<DimPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByTestId('dim-project-edit-1'))
    expect(await screen.findByTestId('dim-project-name')).toHaveValue('订单域')
    fireEvent.change(screen.getByTestId('dim-project-name'), { target: { value: '交易域' } })
    await userEvent.click(modalOk())

    await waitFor(() => {
      const patches = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'PATCH')
      expect(patches).toHaveLength(1)
      expect(patches[0][0]).toBe('/api/admin/dim/projects/1')
      expect(JSON.parse(patches[0][1]?.body as string)).toEqual({ name: '交易域', remark: '订单相关制度' })
    })
    expect(await screen.findAllByText(/已改名为「交易域」，引用文档已联动更新/)).not.toHaveLength(0)
  })

  it('删除引用中项目：409 KB_PROJECT_IN_USE 透传（含引用数）', async () => {
    const fn = stubDefault([project({ docCount: 4 })], [], {
      '/api/admin/dim/projects/1': apiErrorResponse(
        'KB_PROJECT_IN_USE',
        '项目「订单域」仍被 4 篇文档引用，请先调整文档归属后再删除',
        409,
      ),
    })
    render(<DimPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByTestId('dim-project-delete-1'))
    const popupOk = document.querySelector('.ant-popconfirm .ant-btn-dangerous') as HTMLElement
    await userEvent.click(popupOk)

    expect(await screen.findAllByText(/仍被 4 篇文档引用/)).not.toHaveLength(0)
    expect(fn.mock.calls.filter((c) => (c[1] as RequestInit)?.method === 'DELETE')).toHaveLength(1)
  })

  it('删除无引用项目：DELETE 204 → 成功提示并刷新', async () => {
    const fn = stubDefault([project({ docCount: 0 })], [], {
      '/api/admin/dim/projects/1': new Response(null, { status: 204 }),
    })
    render(<DimPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByTestId('dim-project-delete-1'))
    const popupOk = document.querySelector('.ant-popconfirm .ant-btn-dangerous') as HTMLElement
    await userEvent.click(popupOk)

    await waitFor(() =>
      expect(fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'DELETE')).toHaveLength(1),
    )
    expect(await screen.findAllByText(/项目「订单域」已删除/)).not.toHaveLength(0)
  })
})

describe('维度维护页：标签', () => {
  it('标签改名：PATCH {from, to} → 提示联动文档数', async () => {
    const fn = stubDefault([], [tag()], {
      '/api/admin/dim/tags': jsonResponse({ affectedDocs: 2 }),
    })
    render(<DimPage />)
    await screen.findByText('售后')

    await userEvent.click(screen.getByTestId('dim-tag-rename-售后'))
    fireEvent.change(await screen.findByTestId('dim-tag-rename-to'), { target: { value: '换货' } })
    await userEvent.click(modalOk())

    await waitFor(() => {
      const patches = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'PATCH')
      expect(patches).toHaveLength(1)
      expect(JSON.parse(patches[0][1]?.body as string)).toEqual({ from: '售后', to: '换货' })
    })
    expect(await screen.findAllByText(/已改名为「换货」，联动 2 篇文档/)).not.toHaveLength(0)
  })

  it('标签删除：DELETE → 提示从 N 篇文档移除', async () => {
    const fn = stubDefault([], [tag({ name: '退货', docCount: 1 })], {
      '/api/admin/dim/tags/': jsonResponse({ affectedDocs: 1 }),
    })
    render(<DimPage />)
    await screen.findByText('退货')

    await userEvent.click(screen.getByTestId('dim-tag-delete-退货'))
    const popupOk = document.querySelector('.ant-popconfirm .ant-btn-dangerous') as HTMLElement
    await userEvent.click(popupOk)

    await waitFor(() => {
      const deletes = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'DELETE')
      expect(deletes).toHaveLength(1)
      expect(deletes[0][0]).toBe(`/api/admin/dim/tags/${encodeURIComponent('退货')}`)
    })
    expect(await screen.findAllByText(/已从 1 篇文档移除/)).not.toHaveLength(0)
  })

  it('标签改名为非法值：前端预检拦截，不发 PATCH', async () => {
    const fn = stubDefault([], [tag()])
    render(<DimPage />)
    await screen.findByText('售后')

    await userEvent.click(screen.getByTestId('dim-tag-rename-售后'))
    fireEvent.change(await screen.findByTestId('dim-tag-rename-to'), { target: { value: '含,逗号' } })
    await userEvent.click(modalOk())

    expect(await screen.findAllByText(/标签仅支持中文\/字母\/数字\/中划线\/下划线/)).not.toHaveLength(0)
    expect(fn.mock.calls.filter((c) => (c[1] as RequestInit)?.method === 'PATCH')).toHaveLength(0)
  })

  it('projects 加载失败：错误 Alert + 重试恢复', async () => {
    let failed = true
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.endsWith('/api/admin/dim/projects') && method === 'GET')
        return failed ? apiErrorResponse('KB_STORE_FAILED', 'pg down', 502) : jsonResponse([project()])
      if (url.endsWith('/api/admin/dim/tags')) return jsonResponse([])
      return new Response('nf', { status: 404 })
    })
    render(<DimPage />)

    expect(await screen.findByText('项目列表加载失败')).toBeInTheDocument()
    failed = false
    await userEvent.click(screen.getByRole('button', { name: /^重\s*试$/ }))
    expect(await screen.findByText('订单域')).toBeInTheDocument()
  })
})
