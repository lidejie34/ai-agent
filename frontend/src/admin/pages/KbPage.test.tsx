import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import KbPage from './KbPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { KbDocument, KbHealth } from '../types'

// T11（迭代6）：知识库管理页——健康 Alert（手动刷新/无轮询）、multipart 上传、
// 列表字段/FAILED 行、重建索引、删除（Popconfirm 二次确认）、KB_* 错误文案。

const healthy = (over: Partial<KbHealth> = {}): KbHealth => ({
  enabled: true,
  ollamaOk: true,
  pgOk: true,
  documentCount: 2,
  chunkCount: 12,
  dimensions: 1024,
  ...over,
})

const doc = (over: Partial<KbDocument> = {}): KbDocument => ({
  id: 1,
  fileName: '差旅制度.md',
  sizeBytes: 2048,
  chunkCount: 5,
  status: 'READY',
  error: null,
  createdAt: '2026-09-10 10:00:00',
  updatedAt: '2026-09-10 10:05:00',
  ...over,
})

const failedDoc = doc({
  id: 2,
  fileName: 'broken.txt',
  chunkCount: 0,
  status: 'FAILED',
  error: '向量化失败: connect ECONNREFUSED 127.0.0.1:11434',
})

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x')
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  clearAdminToken()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

/** 默认路由：health → 给定健康视图；documents → 给定列表；其余 404。 */
function stubDefault(health: KbHealth, documents: KbDocument[], extra?: Record<string, Response>) {
  return stubFetch((url, init) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/api/admin/kb/health') && method === 'GET') return jsonResponse(health)
    if (url.endsWith('/api/admin/kb/documents') && method === 'GET') return jsonResponse(documents)
    if (extra) {
      const hit = Object.keys(extra).find((k) => url.includes(k))
      if (hit) return extra[hit]
    }
    return new Response('not found', { status: 404 })
  })
}

function fileInput(): HTMLInputElement {
  return document.querySelector('input[type=file]') as HTMLInputElement
}

describe('知识库页：健康 Alert', () => {
  it('进页拉 health+documents（带 X-Admin-Token）；两下游正常显示 success 与计数/维度', async () => {
    const fn = stubDefault(healthy(), [doc()])
    render(<KbPage />)

    expect(await screen.findByText('知识库服务正常')).toBeInTheDocument()
    expect(screen.getByTestId('kb-health-ollama')).toHaveTextContent('正常')
    expect(screen.getByTestId('kb-health-pg')).toHaveTextContent('正常')
    expect(screen.getByText('文档 2 个')).toBeInTheDocument()
    expect(screen.getByText('切片 12 条')).toBeInTheDocument()
    expect(screen.getByText('向量维度 1024')).toBeInTheDocument()

    const urls = fn.mock.calls.map((c) => c[0])
    expect(urls.some((u) => u.includes('/health'))).toBe(true)
    expect(urls.some((u) => u.endsWith('/documents'))).toBe(true)
    for (const call of fn.mock.calls) {
      expect((call[1] as RequestInit).headers).toMatchObject({ 'X-Admin-Token': 'tok-x' })
    }
  })

  it('ollamaOk=false：warning 文案 + Ollama 徽标不可用，PG 仍正常', async () => {
    stubDefault(healthy({ ollamaOk: false, documentCount: 0, chunkCount: 0 }), [])
    render(<KbPage />)

    expect(await screen.findByText('知识库部分依赖不可用：上传与问答增强将失败或降级')).toBeInTheDocument()
    expect(screen.getByTestId('kb-health-ollama')).toHaveTextContent('不可用')
    expect(screen.getByTestId('kb-health-pg')).toHaveTextContent('正常')
  })

  it('health 请求 503 KB_DISABLED：错误 Alert + 中文文案，不影响文档区独立错误展示', async () => {
    stubFetch((url) => {
      if (url.includes('/health')) return apiErrorResponse('KB_DISABLED', '知识库功能未启用', 503)
      if (url.endsWith('/documents')) return jsonResponse([])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)

    expect(await screen.findByText('健康检查失败')).toBeInTheDocument()
    expect(screen.getByText('知识库功能未启用（app.rag.enabled=false）')).toBeInTheDocument()
    expect(screen.getByText('知识库暂无文档，请上传 Markdown / TXT 文件')).toBeInTheDocument()
  })
})

describe('知识库页：文档表格', () => {
  it('READY 行：文件名/大小/切片数/就绪 Tag/更新时间', async () => {
    stubDefault(healthy(), [doc()])
    render(<KbPage />)

    await screen.findByText('差旅制度.md')
    expect(screen.getByText('就绪')).toBeInTheDocument()
    expect(screen.getByText('2.0 KB')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('2026-09-10 10:05:00')).toBeInTheDocument()
    expect(screen.queryByTestId('kb-row-error-icon')).toBeNull()
  })

  it('FAILED 行：失败 Tag、切片数「-」、错误图标 hover 显示完整后端错误', async () => {
    stubDefault(healthy(), [failedDoc])
    render(<KbPage />)

    expect(await screen.findAllByText('失败')).toHaveLength(1)
    expect(screen.getByText('broken.txt')).toBeInTheDocument()
    const icon = await screen.findByTestId('kb-row-error-icon')
    // 切片数列为「-」而非 0
    const cells = icon.closest('tr')?.querySelectorAll('td')
    expect(cells?.[3]).toHaveTextContent('-')

    await userEvent.hover(icon)
    expect(await screen.findByText(/connect ECONNREFUSED 127\.0\.0\.1:11434/)).toBeInTheDocument()
  })

  it('空库：Empty 引导文案', async () => {
    stubDefault(healthy({ documentCount: 0, chunkCount: 0 }), [])
    render(<KbPage />)
    expect(await screen.findByText('知识库暂无文档，请上传 Markdown / TXT 文件')).toBeInTheDocument()
  })

  it('documents 加载失败：错误 Alert + 重试按钮可恢复', async () => {
    let docsFailed = true
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.endsWith('/documents') && method === 'GET')
        return docsFailed
          ? apiErrorResponse('KB_STORE_FAILED', 'pg down', 502)
          : jsonResponse([doc()])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)

    expect(await screen.findByText('文档列表加载失败')).toBeInTheDocument()
    expect(screen.getByText('知识库存储失败（向量库不可用），请稍后重试')).toBeInTheDocument()

    docsFailed = false
    await userEvent.click(screen.getByRole('button', { name: /^重\s*试$/ }))
    expect(await screen.findByText('差旅制度.md')).toBeInTheDocument()
    // 重试只重拉 documents：health 始终 1 次
    expect(fn.mock.calls.filter((c) => c[0].includes('/health'))).toHaveLength(1)
  })
})

describe('知识库页：上传', () => {
  it('选择 md 文件 → POST multipart（字段 file、无手设 Content-Type、带 token）→ 成功提示并刷新列表/健康', async () => {
    const fn = stubDefault(healthy(), [], {
      // POST /api/admin/kb/documents → 201 READY 视图
      '/api/admin/kb/documents': jsonResponse(doc({ id: 7, fileName: '差旅制度.md' }), 201),
    })
    const appendSpy = vi.spyOn(FormData.prototype, 'append')

    render(<KbPage />)
    await screen.findByText('知识库服务正常')
    expect(fn).toHaveBeenCalledTimes(2)

    const file = new File(['# 差旅制度\n正文'], '差旅制度.md', { type: 'text/markdown' })
    await act(async () => {
      fireEvent.change(fileInput(), { target: { files: [file] } })
    })

    await waitFor(() => {
      const posts = fn.mock.calls.filter(
        (c) => (c[1] as RequestInit).method === 'POST' && (c[0] as string).endsWith('/documents'),
      )
      expect(posts).toHaveLength(1)
      const [url, init] = posts[0] as [string, RequestInit]
      expect(url).toBe('/api/admin/kb/documents')
      expect(init.body).toBeInstanceOf(FormData)
      expect(init.headers).toEqual({ 'X-Admin-Token': 'tok-x' })
    })
    expect(appendSpy).toHaveBeenCalledWith('file', expect.any(File))
    expect(await screen.findByText(/上传并向量化完成/)).toBeInTheDocument()

    // 上传成功后文档列表 + 健康计数都重拉：mount 2 + POST 1 + 刷新 2
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(5))
    expect(
      fn.mock.calls.filter((c) => c[0].includes('/health') && (c[1] as RequestInit).method === undefined),
    ).toHaveLength(2)
    expect(
      fn.mock.calls.filter(
        (c) => c[0].endsWith('/documents') && ((c[1] as RequestInit).method ?? 'GET') === 'GET',
      ),
    ).toHaveLength(2)
    appendSpy.mockRestore()
  })

  it('上传 502 KB_EMBEDDING_FAILED：错误 message 走中文映射，列表不被清空', async () => {
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.endsWith('/documents') && method === 'POST')
        return apiErrorResponse('KB_EMBEDDING_FAILED', 'Ollama 500', 502)
      if (url.endsWith('/documents')) return jsonResponse([doc()])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    await act(async () => {
      fireEvent.change(fileInput(), {
        target: { files: [new File(['x'], 'a.md', { type: 'text/markdown' })] },
      })
    })

    expect(await screen.findByText('向量化失败（Embedding 服务不可用），可稍后在列表中重建索引')).toBeInTheDocument()
    expect(screen.getByText('差旅制度.md')).toBeInTheDocument()
  })

  it('上传控件仅接受 .md/.markdown/.txt 且单选', () => {
    stubDefault(healthy(), [])
    render(<KbPage />)
    const input = fileInput()
    expect(input).toHaveAttribute('accept', '.md,.markdown,.txt')
    expect(input).not.toHaveAttribute('multiple') // multiple={false}：单文件上传
  })
})

describe('知识库页：重建 / 删除 / 刷新 / 轮询', () => {
  it('重建索引：POST /documents/{id}/reindex → 成功提示并刷新', async () => {
    const fn = stubDefault(healthy(), [doc()], {
      '/reindex': jsonResponse(doc()),
    })
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    await userEvent.click(screen.getByRole('button', { name: '重建索引' }))

    await waitFor(() => {
      const calls = fn.mock.calls.filter(
        (c) => (c[1] as RequestInit).method === 'POST' && (c[0] as string).includes('/reindex'),
      )
      expect(calls).toHaveLength(1)
      expect(calls[0][0]).toBe('/api/admin/kb/documents/1/reindex')
    })
    expect(await screen.findByText('索引重建完成')).toBeInTheDocument()
  })

  it('删除：Popconfirm 二次确认后 DELETE（204）→ 成功提示并刷新', async () => {
    const fn = stubDefault(healthy(), [doc({ id: 9, fileName: 'old.md' })], {
      '/documents/9': new Response(null, { status: 204 }),
    })
    render(<KbPage />)
    expect(await screen.findByText('old.md')).toBeInTheDocument()

    // antd 双汉字按钮插入空格：accessible name 为「删 除」
    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    // 弹出层里的危险主按钮
    const popupOk = document.querySelector('.ant-popconfirm .ant-btn-dangerous') as HTMLElement
    expect(popupOk).toBeTruthy()
    await userEvent.click(popupOk)

    await waitFor(() => {
      const calls = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'DELETE')
      expect(calls).toHaveLength(1)
      expect(calls[0][0]).toBe('/api/admin/kb/documents/9')
    })
    expect(await screen.findByText('文档及其切片已删除')).toBeInTheDocument()
  })

  it('删除返回 404 KB_NOT_FOUND：提示已被删除并刷新列表', async () => {
    const fn2 = stubFetch((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (method === 'DELETE') return apiErrorResponse('KB_NOT_FOUND', '不存在', 404)
      if (url.endsWith('/documents')) return jsonResponse([doc({ id: 3, fileName: 'x.md' })])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    expect(await screen.findByText('x.md')).toBeInTheDocument()
    const before = fn2.mock.calls.length

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    const popupOk = document.querySelector('.ant-popconfirm .ant-btn-dangerous') as HTMLElement
    await userEvent.click(popupOk)

    expect(await screen.findByText('文档已被删除')).toBeInTheDocument()
    await waitFor(() => expect(fn2.mock.calls.length).toBeGreaterThan(before))
  })

  it('手动刷新：按钮重新请求 health + documents', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(healthy()))
      .mockResolvedValueOnce(jsonResponse([doc()]))
      .mockResolvedValueOnce(jsonResponse(healthy({ chunkCount: 99 })))
      .mockResolvedValueOnce(jsonResponse([doc(), doc({ id: 2, fileName: 'second.md' })]))
    vi.stubGlobal('fetch', fn)
    render(<KbPage />)
    await screen.findByText('差旅制度.md')
    expect(fn).toHaveBeenCalledTimes(2)

    await userEvent.click(screen.getByTestId('kb-refresh'))
    expect(await screen.findByText('second.md')).toBeInTheDocument()
    expect(screen.getByText('切片 99 条')).toBeInTheDocument()
    expect(fn).toHaveBeenCalledTimes(4)
  })

  it('不轮询：推进 10s 定时器，fetch 次数不增长', async () => {
    const fn = stubDefault(healthy(), [doc()])
    render(<KbPage />)
    await screen.findByText('差旅制度.md')
    expect(fn).toHaveBeenCalledTimes(2)

    vi.useFakeTimers()
    act(() => {
      vi.advanceTimersByTime(10_000)
    })
    expect(fn).toHaveBeenCalledTimes(2)
  })
})
