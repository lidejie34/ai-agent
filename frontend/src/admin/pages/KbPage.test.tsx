import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import KbPage from './KbPage'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { DimProject, KbDocument, KbHealth } from '../types'

// T11（迭代6）：知识库管理页——健康 Alert（手动刷新/无轮询）、multipart 上传、
// 列表字段/FAILED 行、重建索引、删除（Popconfirm 二次确认）、KB_* 错误文案。
// 迭代10：项目/标签两列、过滤区（查询/重置）、上传弹窗打标、编辑标签弹窗 PATCH 全量替换。

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
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  clearAdminToken()
  // 注意：jsdom 无 CSS 过渡，rc-notification 离场动画永不完成，message.destroy()
  // 实际无法移除节点——同文案 toast 会跨用例残留，断言一律用 findAllByText 兜底
  message.destroy()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

/** 维度项目视图（迭代10 追加：项目受管，页面挂载拉 /api/admin/dim/projects）。 */
const dimProject = (id: number, name: string): DimProject => ({
  id,
  name,
  remark: null,
  docCount: 0,
  createdAt: '2026-09-15 10:00:00',
  updatedAt: '2026-09-15 10:00:00',
})

/** 默认路由：health → 给定健康视图；documents → 给定列表；dim/projects → 受管项目；其余 404。 */
function stubDefault(
  health: KbHealth,
  documents: KbDocument[],
  extra?: Record<string, Response>,
  projects: string[] = [],
) {
  return stubFetch((url, init) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/api/admin/kb/health') && method === 'GET') return jsonResponse(health)
    if (url.endsWith('/api/admin/kb/documents') && method === 'GET') return jsonResponse(documents)
    if (url.includes('/api/admin/dim/projects') && method === 'GET')
      return jsonResponse(projects.map((p, i) => dimProject(i + 1, p)))
    if (extra) {
      const hit = Object.keys(extra).find((k) => url.includes(k))
      if (hit) return extra[hit]
    }
    return new Response('not found', { status: 404 })
  })
}

/** antd Select 选择：打开下拉并点击选项文本（选项渲染在 body 弹层）。 */
async function selectOption(testId: string, optionText: string) {
  const root = screen.getByTestId(testId)
  fireEvent.mouseDown(root.querySelector('.ant-select-selector') as HTMLElement)
  const option = await screen.findByText(optionText, { selector: '.ant-select-item-option-content' })
  await userEvent.click(option)
}

/** 单选 Select 当前选中项文本；未选中返回 null。 */
function selectValue(testId: string): string | null {
  return screen.getByTestId(testId).querySelector('.ant-select-selection-item')?.textContent ?? null
}

/** 清除单选 Select（allowClear 图标常驻 DOM，jsdom 可直接点击）。 */
async function clearSelect(testId: string) {
  const root = screen.getByTestId(testId)
  await userEvent.click(root.querySelector('.ant-select-clear') as HTMLElement)
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
    // 切片数列为「-」而非 0（迭代10：0=ID,1=文件名,2=项目,3=标签,4=大小,5=切片数）
    const cells = icon.closest('tr')?.querySelectorAll('td')
    expect(cells?.[5]).toHaveTextContent('-')

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

/** 打开上传弹窗并选中文件（迭代10：上传迁入弹窗，文件本地暂存待「上传」提交）。 */
async function openUploadAndPick(file: File) {
  await userEvent.click(screen.getByTestId('kb-upload'))
  await screen.findByText('选择文件')
  await act(async () => {
    fireEvent.change(fileInput(), { target: { files: [file] } })
  })
  expect(await screen.findByTestId('kb-upload-filename')).toHaveTextContent(`已选择：${file.name}`)
}

/** 弹窗底部主按钮（上传/保存）。 */
function modalOk(): HTMLElement {
  return document.querySelector('.ant-modal-footer .ant-btn-primary') as HTMLElement
}

describe('知识库页：上传', () => {
  it('弹窗选 md 文件 → POST multipart（字段 file、无手设 Content-Type、带 token）→ 成功提示并刷新列表/健康', async () => {
    const fn = stubDefault(healthy(), [], {
      // POST /api/admin/kb/documents → 201 READY 视图
      '/api/admin/kb/documents': jsonResponse(doc({ id: 7, fileName: '差旅制度.md' }), 201),
    })
    const appendSpy = vi.spyOn(FormData.prototype, 'append')

    render(<KbPage />)
    await screen.findByText('知识库服务正常')
    // mount：health + documents + dim/projects
    expect(fn).toHaveBeenCalledTimes(3)

    await openUploadAndPick(new File(['# 差旅制度\n正文'], '差旅制度.md', { type: 'text/markdown' }))
    await userEvent.click(modalOk())

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
    // 未填 meta：不带 project/tags 字段
    expect(appendSpy.mock.calls.map((c) => c[0])).not.toContain('project')
    expect(appendSpy.mock.calls.map((c) => c[0])).not.toContain('tags')
    expect(await screen.findByText(/上传并向量化完成/)).toBeInTheDocument()

    // 上传成功后文档列表 + 健康计数都重拉：mount 3 + POST 1 + 刷新 2
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(6))
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

  it('上传带项目/标签：项目下拉选择受管项目，form 附 project 与逗号拼接 tags', async () => {
    stubDefault(
      healthy(),
      [],
      {
        '/api/admin/kb/documents': jsonResponse(doc({ id: 8, fileName: '售后.md' }), 201),
      },
      ['订单域'],
    )
    const appendSpy = vi.spyOn(FormData.prototype, 'append')

    render(<KbPage />)
    await screen.findByText('知识库服务正常')
    await openUploadAndPick(new File(['内容'], '售后.md', { type: 'text/markdown' }))
    await selectOption('kb-upload-project', '订单域')
    fireEvent.change(screen.getByTestId('kb-upload-tags'), { target: { value: '售后, 退货，,售后' } })
    await userEvent.click(modalOk())

    await waitFor(() => expect(appendSpy).toHaveBeenCalledWith('project', '订单域'))
    expect(appendSpy).toHaveBeenCalledWith('tags', '售后,退货')
    // 前序用例的成功 toast 可能仍挂在 body：断言本用例文件名
    expect(await screen.findByText(/「售后\.md」上传并向量化完成/)).toBeInTheDocument()
    appendSpy.mockRestore()
  })

  it('上传 400 KB_INVALID_PROJECT（项目未受管）：透传后端 message', async () => {
    stubDefault(
      healthy(),
      [],
      {
        '/api/admin/kb/documents': apiErrorResponse(
          'KB_INVALID_PROJECT',
          '项目不存在，请先在「维度维护」页创建项目：订单域',
          400,
        ),
      },
      ['订单域'],
    )
    render(<KbPage />)
    await screen.findByText('知识库服务正常')

    await openUploadAndPick(new File(['x'], 'a.md', { type: 'text/markdown' }))
    await selectOption('kb-upload-project', '订单域')
    await userEvent.click(modalOk())

    expect(
      await screen.findByText(/项目不存在，请先在「维度维护」页创建项目/),
    ).toBeInTheDocument()
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

    await openUploadAndPick(new File(['x'], 'a.md', { type: 'text/markdown' }))
    await userEvent.click(modalOk())

    expect(await screen.findByText('向量化失败（Embedding 服务不可用），可稍后在列表中重建索引')).toBeInTheDocument()
    expect(screen.getByText('差旅制度.md')).toBeInTheDocument()
  })

  it('上传控件仅接受 .md/.markdown/.txt 且单选', async () => {
    stubDefault(healthy(), [])
    render(<KbPage />)
    await userEvent.click(await screen.findByTestId('kb-upload'))
    const input = fileInput()
    expect(input).toHaveAttribute('accept', '.md,.markdown,.txt')
    expect(input).not.toHaveAttribute('multiple') // multiple={false}：单文件上传
  })
})

describe('知识库页：项目/标签列与过滤（迭代10）', () => {
  it('列表渲染项目/标签两列；缺省显示「-」', async () => {
    stubDefault(healthy(), [
      doc({ id: 1, project: '订单域', tags: ['售后', '退货'] }),
      doc({ id: 2, fileName: '无标.md' }),
    ])
    render(<KbPage />)

    await screen.findByText('差旅制度.md')
    expect(screen.getByText('订单域')).toBeInTheDocument()
    expect(screen.getByText('售后')).toBeInTheDocument()
    expect(screen.getByText('退货')).toBeInTheDocument()
    const plainRow = (await screen.findByText('无标.md')).closest('tr')
    expect(plainRow?.querySelectorAll('td')[2]).toHaveTextContent('-')
    expect(plainRow?.querySelectorAll('td')[3]).toHaveTextContent('-')
  })

  it('过滤查询：项目下拉选择 + 标签输入 → GET documents 带 project/tag 查询参数', async () => {
    const fn = stubDefault(healthy(), [doc({ project: '订单域', tags: ['售后'] })], undefined, ['订单域'])
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    await selectOption('kb-filter-project', '订单域')
    fireEvent.change(screen.getByTestId('kb-filter-tag'), { target: { value: '售后' } })
    await userEvent.click(screen.getByTestId('kb-filter-apply'))

    await waitFor(() => {
      const filtered = fn.mock.calls.filter((c) => (c[0] as string).includes('project='))
      expect(filtered).toHaveLength(1)
      const u = new URL(filtered[0][0] as string, 'http://localhost')
      expect(u.searchParams.get('project')).toBe('订单域')
      expect(u.searchParams.get('tag')).toBe('售后')
    })
  })

  it('重置：清空输入并重新全量查询（无查询参数）', async () => {
    const fn = stubDefault(healthy(), [doc()])
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    fireEvent.change(screen.getByTestId('kb-filter-tag'), { target: { value: '售后' } })
    await userEvent.click(screen.getByTestId('kb-filter-apply'))
    await waitFor(() =>
      expect(fn.mock.calls.some((c) => (c[0] as string).includes('tag='))).toBe(true),
    )

    await userEvent.click(screen.getByTestId('kb-filter-reset'))
    await waitFor(() => {
      const last = fn.mock.calls[fn.mock.calls.length - 1]
      expect((last[0] as string).endsWith('/documents')).toBe(true)
    })
    expect(selectValue('kb-filter-project')).toBeNull()
    expect(screen.getByTestId('kb-filter-tag')).toHaveValue('')
  })

  it('过滤值非法（标签含空格）：前端预检拦截，不发请求', async () => {
    const fn = stubDefault(healthy(), [doc()])
    render(<KbPage />)
    await screen.findByText('差旅制度.md')
    const before = fn.mock.calls.length

    fireEvent.change(screen.getByTestId('kb-filter-tag'), { target: { value: '售 后' } })
    await userEvent.click(screen.getByTestId('kb-filter-apply'))

    expect(await screen.findByText(/标签「售 后」非法/)).toBeInTheDocument()
    expect(fn.mock.calls.length).toBe(before)
  })

  it('过滤后无结果：Empty 过滤文案', async () => {
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.includes('/documents') && method === 'GET') return jsonResponse([])
      if (url.includes('/api/admin/dim/projects')) return jsonResponse([dimProject(1, '订单域')])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('知识库暂无文档，请上传 Markdown / TXT 文件')

    await selectOption('kb-filter-project', '订单域')
    await userEvent.click(screen.getByTestId('kb-filter-apply'))

    expect(await screen.findByText('没有匹配过滤条件的文档，可调整项目/标签后重新查询')).toBeInTheDocument()
  })

  it('列表过滤 400 KB_INVALID_TAGS：错误区透传后端文案', async () => {
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.includes('tag=') && method === 'GET')
        return apiErrorResponse('KB_INVALID_TAGS', '标签仅支持中文/字母/数字/中划线/下划线：「x,y」', 400)
      if (url.endsWith('/documents')) return jsonResponse([doc()])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    // 绕过前端预检：直接构造合法预检但后端拒绝的场景（此处用合法值，桩按 tag= 拒绝）
    fireEvent.change(screen.getByTestId('kb-filter-tag'), { target: { value: 'xy' } })
    await userEvent.click(screen.getByTestId('kb-filter-apply'))

    expect(await screen.findByText('文档列表加载失败')).toBeInTheDocument()
    expect(screen.getByText(/标签仅支持中文\/字母\/数字\/中划线\/下划线/)).toBeInTheDocument()
  })
})

describe('知识库页：编辑标签弹窗（迭代10 PATCH 全量替换）', () => {
  it('打开预填现有 project/tags；修改保存 → PATCH body 全量 → 成功提示并刷新', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.endsWith('/documents/1') && method === 'PATCH')
        return jsonResponse(doc({ id: 1, project: '物流域', tags: ['承运'] }))
      if (url.endsWith('/documents') && method === 'GET')
        return jsonResponse([doc({ id: 1, project: '订单域', tags: ['售后', '退货'] })])
      if (url.includes('/api/admin/dim/projects'))
        return jsonResponse([dimProject(1, '订单域'), dimProject(2, '物流域')])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByRole('button', { name: '编辑标签' }))
    // 预填（Select 选中项 + 标签文本框）
    expect(await screen.findByTestId('kb-meta-project')).toBeInTheDocument()
    expect(selectValue('kb-meta-project')).toBe('订单域')
    expect(screen.getByTestId('kb-meta-tags')).toHaveValue('售后,退货')

    await selectOption('kb-meta-project', '物流域')
    fireEvent.change(screen.getByTestId('kb-meta-tags'), { target: { value: '承运' } })
    await userEvent.click(modalOk())

    await waitFor(() => {
      const patches = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'PATCH')
      expect(patches).toHaveLength(1)
      expect(patches[0][0]).toBe('/api/admin/kb/documents/1')
      expect(JSON.parse(patches[0][1]?.body as string)).toEqual({ project: '物流域', tags: ['承运'] })
    })
    expect(await screen.findByText(/的项目\/标签已更新/)).toBeInTheDocument()
    // 保存后重拉列表
    await waitFor(() =>
      expect(
        fn.mock.calls.filter(
          (c) => c[0].endsWith('/documents') && ((c[1] as RequestInit).method ?? 'GET') === 'GET',
        ).length,
      ).toBeGreaterThanOrEqual(2),
    )
  })

  it('清空项目/标签保存 → PATCH body { project: null, tags: [] }', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (url.endsWith('/documents/1') && method === 'PATCH') return jsonResponse(doc({ id: 1 }))
      if (url.endsWith('/documents') && method === 'GET')
        return jsonResponse([doc({ id: 1, project: '订单域', tags: ['售后'] })])
      if (url.includes('/api/admin/dim/projects')) return jsonResponse([dimProject(1, '订单域')])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByRole('button', { name: '编辑标签' }))
    expect(selectValue('kb-meta-project')).toBe('订单域')
    await clearSelect('kb-meta-project')
    fireEvent.change(screen.getByTestId('kb-meta-tags'), { target: { value: '' } })
    await userEvent.click(modalOk())

    await waitFor(() => {
      const patches = fn.mock.calls.filter((c) => (c[1] as RequestInit).method === 'PATCH')
      expect(patches).toHaveLength(1)
      expect(JSON.parse(patches[0][1]?.body as string)).toEqual({ project: null, tags: [] })
    })
  })

  it('PATCH 400 KB_INVALID_PROJECT：透传后端 message', async () => {
    stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (method === 'PATCH')
        return apiErrorResponse('KB_INVALID_PROJECT', '项目名仅支持中文/字母/数字/中划线/下划线：「xx」', 400)
      if (url.endsWith('/documents')) return jsonResponse([doc({ id: 1, project: '订单域' })])
      if (url.includes('/api/admin/dim/projects'))
        return jsonResponse([dimProject(1, '订单域'), dimProject(2, '新域')])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('订单域')

    await userEvent.click(screen.getByRole('button', { name: '编辑标签' }))
    await screen.findByTestId('kb-meta-project')
    await selectOption('kb-meta-project', '新域')
    await userEvent.click(modalOk())

    expect(await screen.findByText(/项目名仅支持中文\/字母\/数字\/中划线\/下划线：「xx」/)).toBeInTheDocument()
  })

  it('PATCH 404 KB_NOT_FOUND：提示已被删除并关闭弹窗刷新', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/health')) return jsonResponse(healthy())
      if (method === 'PATCH') return apiErrorResponse('KB_NOT_FOUND', '文档不存在', 404)
      if (url.endsWith('/documents')) return jsonResponse([doc({ id: 1 })])
      return new Response('nf', { status: 404 })
    })
    render(<KbPage />)
    await screen.findByText('差旅制度.md')

    await userEvent.click(screen.getByRole('button', { name: '编辑标签' }))
    await screen.findByTestId('kb-meta-project')
    const before = fn.mock.calls.length
    await userEvent.click(modalOk())

    expect(await screen.findAllByText('文档已被删除')).not.toHaveLength(0)
    await waitFor(() => expect(fn.mock.calls.length).toBeGreaterThan(before))
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

    // jsdom 中前序用例同文案 toast 残留：findAllByText 兜底；严格性由 DELETE 恰好一次保证
    expect(await screen.findAllByText('文档已被删除')).not.toHaveLength(0)
    expect(fn2.mock.calls.filter((c) => (c[1] as RequestInit)?.method === 'DELETE')).toHaveLength(1)
    await waitFor(() => expect(fn2.mock.calls.length).toBeGreaterThan(before))
  })

  it('手动刷新：按钮重新请求 health + documents', async () => {
    const fn = vi
      .fn()
      // mount 顺序：health → dim/projects → documents；刷新只重拉 health + documents
      .mockResolvedValueOnce(jsonResponse(healthy()))
      .mockResolvedValueOnce(jsonResponse([])) // dim/projects
      .mockResolvedValueOnce(jsonResponse([doc()]))
      .mockResolvedValueOnce(jsonResponse(healthy({ chunkCount: 99 })))
      .mockResolvedValueOnce(jsonResponse([doc(), doc({ id: 2, fileName: 'second.md' })]))
    vi.stubGlobal('fetch', fn)
    render(<KbPage />)
    await screen.findByText('差旅制度.md')
    expect(fn).toHaveBeenCalledTimes(3)

    await userEvent.click(screen.getByTestId('kb-refresh'))
    expect(await screen.findByText('second.md')).toBeInTheDocument()
    expect(screen.getByText('切片 99 条')).toBeInTheDocument()
    expect(fn).toHaveBeenCalledTimes(5)
  })

  it('不轮询：推进 10s 定时器，fetch 次数不增长', async () => {
    const fn = stubDefault(healthy(), [doc()])
    render(<KbPage />)
    await screen.findByText('差旅制度.md')
    // mount：health + documents + dim/projects，此后无轮询
    expect(fn).toHaveBeenCalledTimes(3)

    vi.useFakeTimers()
    act(() => {
      vi.advanceTimersByTime(10_000)
    })
    expect(fn).toHaveBeenCalledTimes(3)
  })
})
