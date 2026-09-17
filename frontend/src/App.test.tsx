import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import App from './App'
import { controllableSse, encodeSse } from './test/sseMock'
import type { ApiError, SessionSummary } from './types'

const SID = '123e4567-e89b-12d3-a456-426614174000'

function sessionSummary(over: Partial<SessionSummary> = {}): SessionSummary {
  return {
    sessionId: SID,
    title: '既有会话',
    createdAt: '2026-09-01 10:00:00',
    updatedAt: '2026-09-03 14:00:00',
    previewRole: 'user',
    previewText: '历史预览',
    ...over,
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

interface SetupOpts {
  initialSessions?: SessionSummary[]
  laterSessions?: SessionSummary[]
  messagesResponse?: () => Response
}

function setupApp(opts: SetupOpts = {}) {
  const sse = controllableSse()
  let listCalls = 0
  const fetchFn = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/api/chat/stream')) {
      // 接线 AbortSignal → 流错误（同 mockControllableFetch）
      init?.signal?.addEventListener('abort', () => {
        try {
          sse.error(new DOMException('Aborted', 'AbortError'))
        } catch {
          /* 流已关闭 */
        }
      })
      return sse.response
    }
    if (url.includes('/context/clear')) {
      return json({ id: 900, createdAt: '2026-09-17 10:00:00' })
    }
    if (url.includes('/batch-delete')) {
      const body = init?.body ? (JSON.parse(String(init.body)) as { ids: string[] }) : { ids: [] }
      return json({ deleted: body.ids, notFound: [] })
    }
    if (url.includes('/messages')) {
      // 迭代13：DELETE（删单轮 /messages/{id}、截断 /messages?fromId=）与 GET 历史区分
      if (method === 'DELETE') return json({ deleted: true })
      return opts.messagesResponse ? opts.messagesResponse() : json([])
    }
    if (url.includes('/api/sessions')) {
      if (method === 'DELETE') return json({ deleted: true })
      if (method === 'PATCH') return json(sessionSummary({ title: '新标题' }))
      listCalls += 1
      if (listCalls === 1) return json(opts.initialSessions ?? [])
      return json(opts.laterSessions ?? opts.initialSessions ?? [])
    }
    return new Response('not found', { status: 404 })
  })
  vi.stubGlobal('fetch', fetchFn)
  return { sse, fetchFn }
}

beforeEach(() => {
  // 隔离 localStorage：恢复逻辑/草稿持久化不串扰相邻用例
  localStorage.clear()
})

afterEach(() => {
  message.destroy() // 清掉静态 message 挂在 body 上的通知
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('App 集成（AC-17~29）', () => {
  it('发送消息：乐观渲染用户消息与流式回复；停止后片段保留并标注「已停止」', async () => {
    const { sse } = setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))
    expect(await screen.findByText('你好')).toBeInTheDocument()

    await act(async () => {
      sse.push(encodeSse.session(SID))
      sse.push(encodeSse.chunk('你好呀'))
    })
    expect(await screen.findByText('你好呀')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('chat-stop'))
    expect(await screen.findByText('已停止')).toBeInTheDocument()
  })

  it('流式中切换会话被阻止：提示「生成中，请先停止」且不加载历史', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '提问中' } })
    await userEvent.click(screen.getByTestId('chat-send'))
    await screen.findByText('提问中')

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    expect(await screen.findByText('生成中，请先停止')).toBeInTheDocument()
    // 没有发起历史消息请求
    expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/messages'))).toBe(false)
  })

  it('删除当前会话：消息区清空回空态，侧边栏移除', async () => {
    const { sse } = setupApp({ initialSessions: [], laterSessions: [sessionSummary()] })
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '第一轮' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await act(async () => {
      sse.push(encodeSse.session(SID))
      sse.push(encodeSse.chunk('回复内容'))
      sse.push(encodeSse.done())
      sse.close()
    })
    // done 后 refresh → 侧边栏出现该会话
    expect(await screen.findByTestId(`session-item-${SID}`)).toBeInTheDocument()
    expect(await screen.findByText('回复内容')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId(`delete-btn-${SID}`))
    await screen.findByText('确定删除该会话及其全部消息？')
    await userEvent.click(await screen.findByTestId(`delete-confirm-${SID}`))

    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
    expect(screen.queryByText('回复内容')).toBeNull()
  })

  it('切换到已删除会话（404）：侧边栏移除、主区回空态，且无错误提示', async () => {
    const notFound: ApiError = {
      code: 'SESSION_NOT_FOUND',
      message: '会话不存在或已被删除',
      timestamp: '',
    }
    setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => json(notFound, 404),
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await waitFor(() => expect(screen.queryByTestId(`session-item-${SID}`)).toBeNull())
    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
    expect(screen.queryByTestId('inline-error')).toBeNull()
  })

  it('空态点击示例卡片直接发送', async () => {
    const { sse, fetchFn } = setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    const card = screen.getByTestId('example-card-0')
    const prompt = card.textContent ?? ''
    await userEvent.click(card)

    expect(await screen.findByText(prompt)).toBeInTheDocument()
    expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/api/chat/stream'))).toBe(true)
    sse.close()
  })

  it('记忆开关存在且默认开启（记住本次对话）', () => {
    setupApp()
    render(<App />)
    const sw = screen.getByTestId('remember-switch')
    expect(sw).toBeChecked()
  })

  it('知识库维度可用：选择器渲染；选项目（多选）/标签后发送，请求体带 kbProjects/kbTags', async () => {
    const { fetchFn } = setupApp()
    // 在默认路由上叠加 dimensions 路由（setupApp 默认 404 → 选择器隐藏）
    const original = fetchFn.getMockImplementation()!
    fetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).includes('/api/kb/dimensions')) {
        return json({ projects: ['订单域', '物流域'], tags: ['售后', '退货'] })
      }
      return original(url, init)
    })
    render(<App />)

    const bar = await screen.findByTestId('kb-filter-bar')
    // 项目多选（迭代11）：打开下拉连选「订单域」「物流域」
    const projectSel = screen.getByTestId('chat-kb-project')
    fireEvent.mouseDown(projectSel.querySelector('.ant-select-selector') as HTMLElement)
    await userEvent.click(
      await screen.findByText('订单域', { selector: '.ant-select-item-option-content' }),
    )
    await userEvent.click(
      await screen.findByText('物流域', { selector: '.ant-select-item-option-content' }),
    )
    await userEvent.keyboard('{Escape}')
    // 标签多选：选「售后」
    const tagsSel = screen.getByTestId('chat-kb-tags')
    fireEvent.mouseDown(tagsSel.querySelector('.ant-select-selector') as HTMLElement)
    await userEvent.click(
      await screen.findByText('售后', { selector: '.ant-select-item-option-content' }),
    )
    expect(bar).toBeInTheDocument()

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '查退货规则' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.kbProjects).toEqual(['订单域', '物流域'])
      expect(body.kbTags).toEqual(['售后'])
    })
  })

  it('知识库维度不可用（404/空）：选择器隐藏，发送不带维度键', async () => {
    const { fetchFn } = setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    expect(screen.queryByTestId('kb-filter-bar')).toBeNull()

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body).not.toHaveProperty('kbProjects')
      expect(body).not.toHaveProperty('kbTags')
    })
  })

  // ---- 迭代12：对话级工具/MCP 选择 + 会话级范围持久化 ----

  /** 在默认路由上叠加 tools/scope 路由。 */
  function withToolsRoutes(
    fetchFn: ReturnType<typeof vi.fn>,
    scopeGet: unknown = { kbProjects: null, kbTags: null, toolNames: null, mcpServers: null },
  ) {
    const original = fetchFn.getMockImplementation()!
    fetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      const u = String(url)
      if (u.includes('/api/tools/available')) {
        return json({
          dbTools: [
            { name: 'analyze_log', description: '' },
            { name: 'log_error_count', description: '' },
          ],
          mcpServers: [
            { name: 'easy-mysql', status: 'READY', tools: ['easy-mysql_query'] },
            { name: 'dead-server', status: 'UNAVAILABLE', tools: [] },
          ],
        })
      }
      if (u.includes('/scope')) {
        if ((init?.method ?? 'GET') === 'PUT') return json(JSON.parse(String(init?.body)))
        return json(scopeGet)
      }
      return original(url, init)
    })
  }

  it('工具可用：选择器渲染（UNAVAILABLE server 不进选项）；关闭开关后发送，请求体显式 [][]（全不挂）', async () => {
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    render(<App />)

    await screen.findByTestId('tool-scope-bar')
    // MCP 下拉只暴露 READY server
    const mcpSel = screen.getByTestId('chat-mcp-servers')
    fireEvent.mouseDown(mcpSel.querySelector('.ant-select-selector') as HTMLElement)
    expect(
      await screen.findByText('easy-mysql', { selector: '.ant-select-item-option-content' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('dead-server', { selector: '.ant-select-item-option-content' }),
    ).toBeNull()
    await userEvent.keyboard('{Escape}')

    // 关闭「启用工具」→ 本轮不挂任何工具
    await userEvent.click(screen.getByTestId('chat-tool-enabled'))
    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.toolNames).toEqual([])
      expect(body.mcpServers).toEqual([])
    })
  })

  it('工具子集：选内置工具后发送，请求体带 toolNames，未选 MCP 侧省略键', async () => {
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    render(<App />)

    await screen.findByTestId('tool-scope-bar')
    const dbSel = screen.getByTestId('chat-tool-names')
    fireEvent.mouseDown(dbSel.querySelector('.ant-select-selector') as HTMLElement)
    await userEvent.click(
      await screen.findByText('analyze_log', { selector: '.ant-select-item-option-content' }),
    )
    await userEvent.keyboard('{Escape}')

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '查日志' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.toolNames).toEqual(['analyze_log'])
      expect(body).not.toHaveProperty('mcpServers')
    })
  })

  it('会话级持久化：有当前会话时改选择器 → 防抖 PUT /scope（三态映射）', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    withToolsRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await screen.findByTestId('tool-scope-bar')

    // 切入既有会话（scope GET 返回全默认）
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/scope'))).toBe(true),
    )
    await new Promise((r) => setTimeout(r, 50)) // 等回填 setState 落定，避免覆盖后续选择

    // 选内置工具子集 → 触发防抖保存
    const dbSel = screen.getByTestId('chat-tool-names')
    fireEvent.mouseDown(dbSel.querySelector('.ant-select-selector') as HTMLElement)
    await userEvent.click(
      await screen.findByText('analyze_log', { selector: '.ant-select-item-option-content' }),
    )
    await userEvent.keyboard('{Escape}')

    await waitFor(
      () => {
        const puts = fetchFn.mock.calls.filter(
          ([u, i]) =>
            String(u).includes(`/api/sessions/${SID}/scope`) &&
            ((i as RequestInit | undefined)?.method ?? 'GET') === 'PUT',
        )
        expect(puts).toHaveLength(1)
        const body = JSON.parse(String((puts[0][1] as RequestInit).body)) as Record<string, unknown>
        // 三态：KB 未选 → null；工具子集；MCP 未选 → null
        expect(body).toEqual({
          kbProjects: null,
          kbTags: null,
          toolNames: ['analyze_log'],
          mcpServers: null,
        })
      },
      { timeout: 2000 },
    )
  })

  it('会话切换回填：GET scope 子集 → 选择器恢复，随后发送带恢复的范围键', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    withToolsRoutes(fetchFn, {
      kbProjects: ['订单域'],
      kbTags: null,
      toolNames: ['analyze_log'],
      mcpServers: ['easy-mysql'],
    })
    // KB 维度也可用，验证 KB 范围一并回填
    const withKb = fetchFn.getMockImplementation()!
    fetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).includes('/api/kb/dimensions')) {
        return json({ projects: ['订单域', '物流域'], tags: ['售后'] })
      }
      return withKb(url, init)
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await screen.findByTestId('tool-scope-bar')

    // 等待回填可见：工具选择器显示已恢复的选中项
    await waitFor(() =>
      expect(screen.getByTestId('chat-tool-names').textContent).toContain('analyze_log'),
    )

    // 回填生效后发送：范围键全部来自持久化值
    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '继续' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.sessionId).toBe(SID)
      expect(body.kbProjects).toEqual(['订单域'])
      expect(body).not.toHaveProperty('kbTags')
      expect(body.toolNames).toEqual(['analyze_log'])
      expect(body.mcpServers).toEqual(['easy-mysql'])
    })
    // 恢复不回写：全程无 PUT
    expect(
      fetchFn.mock.calls.some(
        ([u, i]) =>
          String(u).includes('/scope') && ((i as RequestInit | undefined)?.method ?? 'GET') === 'PUT',
      ),
    ).toBe(false)
  })

  it('新会话首轮绑定：无 sessionId 时改选择器不发 PUT；发送时请求体带范围键', async () => {
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId('tool-scope-bar')

    // 尚无会话：改选择器 → 不得 PUT
    await userEvent.click(screen.getByTestId('chat-tool-enabled'))
    await new Promise((r) => setTimeout(r, 600)) // 越过 400ms 防抖窗
    expect(
      fetchFn.mock.calls.some(
        ([u, i]) =>
          String(u).includes('/scope') && ((i as RequestInit | undefined)?.method ?? 'GET') === 'PUT',
      ),
    ).toBe(false)

    // 首轮发送：请求体携带范围（后端据此 upsert 绑定新会话）
    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))
    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.toolNames).toEqual([])
      expect(body.mcpServers).toEqual([])
    })
  })

  it('工具不可用（404）：选择器隐藏，发送不带工具键（与迭代11 形态一致）', async () => {
    const { fetchFn } = setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    expect(screen.queryByTestId('tool-scope-bar')).toBeNull()

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body).not.toHaveProperty('toolNames')
      expect(body).not.toHaveProperty('mcpServers')
    })
  })

  // ---- 三下拉「都不加载」互斥哨兵 ----

  /** 在默认路由上叠加 KB dimensions 路由。 */
  function withKbRoutes(fetchFn: ReturnType<typeof vi.fn>) {
    const original = fetchFn.getMockImplementation()!
    fetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).includes('/api/kb/dimensions')) {
        return json({ projects: ['订单域', '物流域'], tags: ['售后', '退货'] })
      }
      return original(url, init)
    })
  }

  /** 打开某多选下拉连点若干选项（同一下拉内连选，最后一次 Escape 关闭——
   *  避免 Escape 后立刻重开同一 select 命中 jsdom 中不收尾的离场动画，
   *  该动画期间 antd slide leave-active 置 pointer-events:none）。
   *  同名选项可能同时存在于离场中的旧下拉，须轮询取非 leave 态下拉里的节点。 */
  async function pickOption(testId: string, ...labels: string[]) {
    const sel = screen.getByTestId(testId)
    fireEvent.mouseDown(sel.querySelector('.ant-select-selector') as HTMLElement)
    for (const label of labels) {
      let opt!: HTMLElement
      await waitFor(() => {
        const candidates = screen.getAllByText(label, {
          selector: '.ant-select-item-option-content',
        }) as HTMLElement[]
        const clickable = candidates.filter((el) => {
          const dd = el.closest('.ant-select-dropdown')
          return dd && !dd.className.includes('leave')
        })
        expect(clickable.length).toBeGreaterThan(0)
        opt = clickable[clickable.length - 1]
      })
      fireEvent.click(opt)
    }
    await userEvent.keyboard('{Escape}')
  }

  it('KB「都不加载」：选哨兵互斥清空项目和标签、标签下拉禁用；发送 kbProjects=[] 无 kbTags', async () => {
    const { fetchFn } = setupApp()
    withKbRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId('kb-filter-bar')

    // 先选项目+标签，再选「都不加载」→ 互斥清空
    await pickOption('chat-kb-project', '订单域')
    await pickOption('chat-kb-tags', '售后')
    await pickOption('chat-kb-project', '都不加载')

    const projectSel = screen.getByTestId('chat-kb-project')
    expect(projectSel.textContent).toContain('都不加载')
    expect(projectSel.textContent).not.toContain('订单域')
    expect(screen.getByTestId('chat-kb-tags')).toHaveClass('ant-select-disabled')
    expect(screen.getByTestId('chat-kb-tags').textContent).not.toContain('售后')

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '随便聊聊' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      expect(streams).toHaveLength(1)
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.kbProjects).toEqual([])
      expect(body).not.toHaveProperty('kbTags')
    })
  })

  it('KB 哨兵互斥摘除：哨兵在场再选项目 → 哨兵摘除，发送带子集', async () => {
    const { fetchFn } = setupApp()
    withKbRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId('kb-filter-bar')

    await pickOption('chat-kb-project', '都不加载', '物流域')

    const projectSel = screen.getByTestId('chat-kb-project')
    expect(projectSel.textContent).toContain('物流域')
    expect(projectSel.textContent).not.toContain('都不加载')

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '查承运' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.kbProjects).toEqual(['物流域'])
    })
  })

  it('内置工具「都不加载」：发送 toolNames=[]、mcpServers 省略；两侧互不影响', async () => {
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId('tool-scope-bar')

    await pickOption('chat-tool-names', '都不加载')
    expect(screen.getByTestId('chat-tool-names').textContent).toContain('都不加载')

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.toolNames).toEqual([])
      expect(body).not.toHaveProperty('mcpServers')
    })
  })

  it('MCP 服务「都不加载」：发送 mcpServers=[]、toolNames 省略', async () => {
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId('tool-scope-bar')

    await pickOption('chat-mcp-servers', '都不加载')
    expect(screen.getByTestId('chat-mcp-servers').textContent).toContain('都不加载')

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.mcpServers).toEqual([])
      expect(body).not.toHaveProperty('toolNames')
    })
  })

  it('会话级持久化：选 KB/内置工具哨兵 → 防抖 PUT 显式 []', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    withToolsRoutes(fetchFn)
    withKbRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await screen.findByTestId('tool-scope-bar')

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/scope'))).toBe(true),
    )
    await new Promise((r) => setTimeout(r, 50))

    await pickOption('chat-kb-project', '都不加载')
    await pickOption('chat-tool-names', '都不加载')

    await waitFor(
      () => {
        const puts = fetchFn.mock.calls.filter(
          ([u, i]) =>
            String(u).includes(`/api/sessions/${SID}/scope`) &&
            ((i as RequestInit | undefined)?.method ?? 'GET') === 'PUT',
        )
        expect(puts.length).toBeGreaterThan(0)
        const body = JSON.parse(
          String((puts[puts.length - 1][1] as RequestInit).body),
        ) as Record<string, unknown>
        expect(body).toEqual({
          kbProjects: [],
          kbTags: null,
          toolNames: [],
          mcpServers: null,
        })
      },
      { timeout: 2000 },
    )
  })

  it('会话切换回填：kbProjects=[] → 项目显「都不加载」；toolNames=[] 单侧 → 内置工具显「都不加载」且开关仍开', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    withToolsRoutes(fetchFn, {
      kbProjects: [],
      kbTags: null,
      toolNames: [],
      mcpServers: ['easy-mysql'],
    })
    withKbRoutes(fetchFn)
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await screen.findByTestId('tool-scope-bar')

    // 回填可见：KB 项目与内置工具各显「都不加载」；MCP 显 easy-mysql；开关仍开（迭代12 单侧 [] 推导）
    await waitFor(() =>
      expect(screen.getByTestId('chat-kb-project').textContent).toContain('都不加载'),
    )
    expect(screen.getByTestId('chat-kb-tags')).toHaveClass('ant-select-disabled')
    expect(screen.getByTestId('chat-tool-names').textContent).toContain('都不加载')
    expect(screen.getByTestId('chat-mcp-servers').textContent).toContain('easy-mysql')
    expect(screen.getByTestId('chat-tool-enabled')).toBeChecked()

    // 回填生效后发送：显式 [] 两侧 + MCP 子集
    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '继续' } })
    await userEvent.click(screen.getByTestId('chat-send'))

    await waitFor(() => {
      const streams = fetchFn.mock.calls.filter(([u]) => String(u).includes('/api/chat/stream'))
      const body = JSON.parse(String((streams[0][1] as RequestInit).body)) as Record<string, unknown>
      expect(body.kbProjects).toEqual([])
      expect(body).not.toHaveProperty('kbTags')
      expect(body.toolNames).toEqual([])
      expect(body.mcpServers).toEqual(['easy-mysql'])
    })
  })

  it('范围工具条（界面美化）：KB 或工具任一可用即渲染 chat-scope-bar 包裹，均不可用则缺席', async () => {
    // 两者皆可用 → 包裹条渲染且内含两个选择器
    const { fetchFn } = setupApp()
    withToolsRoutes(fetchFn)
    withKbRoutes(fetchFn)
    const { unmount } = render(<App />)
    const bar = await screen.findByTestId('chat-scope-bar')
    expect(bar).toBeInTheDocument()
    expect(screen.getByTestId('kb-filter-bar')).toBeInTheDocument()
    expect(screen.getByTestId('tool-scope-bar')).toBeInTheDocument()
    unmount()

    // 均不可用（404）→ 包裹条缺席
    setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())
    expect(screen.queryByTestId('chat-scope-bar')).toBeNull()
    expect(screen.queryByTestId('kb-filter-bar')).toBeNull()
    expect(screen.queryByTestId('tool-scope-bar')).toBeNull()
  })

  it('会话切换回填（迭代12 回归）：toolNames/mcpServers 皆 [] → 推导开关关闭', async () => {
    const { fetchFn } = setupApp({ initialSessions: [sessionSummary()] })
    withToolsRoutes(fetchFn, {
      kbProjects: null,
      kbTags: null,
      toolNames: [],
      mcpServers: [],
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)

    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/scope'))).toBe(true),
    )
    await new Promise((r) => setTimeout(r, 50))

    // 开关关闭 → 两个多选整体不渲染
    expect(screen.getByTestId('chat-tool-enabled')).not.toBeChecked()
    expect(screen.queryByTestId('chat-tool-names')).toBeNull()
    expect(screen.queryByTestId('chat-mcp-servers')).toBeNull()
  })
})

// ---------- 迭代13：消息级删除/截断 + 清空上下文 + 批量删会话 ----------

/** 历史消息视图构造（迭代13：带库 id；role 可 context_reset）。 */
function msgView(id: number, role: 'user' | 'assistant' | 'context_reset', content: string) {
  return { id, role, content, createdAt: '2026-09-17 10:00:00' }
}

describe('App 集成（迭代13：对话删除 + 上下文删除）', () => {
  it('删除本轮：气泡操作 → Popconfirm → DELETE /messages/{id} → 历史重载', async () => {
    let historyCalls = 0
    const { fetchFn } = setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => {
        historyCalls += 1
        return historyCalls === 1
          ? json([msgView(11, 'user', '第一轮问题'), msgView(12, 'assistant', '第一轮回答')])
          : json([])
      },
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    expect(await screen.findByText('第一轮问题')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('msg-delete-turn-11'))
    await userEvent.click(await screen.findByTestId('msg-delete-turn-confirm-11'))

    await waitFor(() =>
      expect(
        fetchFn.mock.calls.some(
          ([u, i]) =>
            String(u).includes(`/api/sessions/${SID}/messages/11`) &&
            (i as RequestInit | undefined)?.method === 'DELETE',
        ),
      ).toBe(true),
    )
    // 删除后重载历史（第二次 GET /messages）→ 列表清空回空态
    await waitFor(() => expect(screen.queryByText('第一轮问题')).toBeNull())
  })

  it('截断重问：DELETE ?fromId= 正确 + 被截内容回填输入框', async () => {
    let historyCalls = 0
    const { fetchFn } = setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => {
        historyCalls += 1
        return historyCalls === 1
          ? json([
              msgView(11, 'user', '保留的问题'),
              msgView(12, 'assistant', '保留的回答'),
              msgView(13, 'user', '要重问的问题'),
              msgView(14, 'assistant', '旧回答'),
            ])
          : json([
              msgView(11, 'user', '保留的问题'),
              msgView(12, 'assistant', '保留的回答'),
            ])
      },
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    expect(await screen.findByText('要重问的问题')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('msg-truncate-13'))
    await userEvent.click(await screen.findByTestId('msg-truncate-confirm-13'))

    await waitFor(() =>
      expect(
        fetchFn.mock.calls.some(
          ([u, i]) =>
            String(u).includes(`/api/sessions/${SID}/messages?fromId=13`) &&
            (i as RequestInit | undefined)?.method === 'DELETE',
        ),
      ).toBe(true),
    )
    // 被截消息内容回填输入框；历史重载后旧轮消失
    expect((screen.getByTestId('chat-input') as HTMLTextAreaElement).value).toBe('要重问的问题')
    await waitFor(() => expect(screen.queryByText('旧回答')).toBeNull())
  })

  it('分隔线：context_reset 标记渲染为「上下文已清空」分隔线且无操作按钮', async () => {
    setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () =>
        json([
          msgView(11, 'user', '标记前的问题'),
          msgView(50, 'context_reset', ''),
          msgView(51, 'user', '标记后的问题'),
        ]),
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))

    expect(await screen.findByTestId('context-divider')).toHaveTextContent(
      '上下文已清空 · 以上历史不再携带',
    )
    expect(screen.getByText('标记前的问题')).toBeInTheDocument()
    // 标记前后消息照常渲染且都带操作按钮；分隔线本身无操作
    expect(screen.getByTestId('msg-actions-11')).toBeInTheDocument()
    expect(screen.getByTestId('msg-actions-51')).toBeInTheDocument()
    expect(screen.queryByTestId('msg-actions-50')).toBeNull()
  })

  it('清空上下文：顶栏 ⋯ → 清空上下文 → Modal 确认 → POST context/clear', async () => {
    const { fetchFn } = setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => json([msgView(11, 'user', '问题'), msgView(12, 'assistant', '回答')]),
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await screen.findByText('问题')

    await userEvent.click(screen.getByTestId('header-more-menu'))
    await userEvent.click(await screen.findByText('清空上下文'))
    await userEvent.click(await screen.findByTestId('clear-context-confirm'))

    await waitFor(() =>
      expect(
        fetchFn.mock.calls.some(
          ([u, i]) =>
            String(u).includes(`/api/sessions/${SID}/context/clear`) &&
            (i as RequestInit | undefined)?.method === 'POST',
        ),
      ).toBe(true),
    )
  })

  it('删除当前会话：顶栏 ⋯ → 删除当前会话 → Modal 确认 → DELETE + 回空态', async () => {
    const { fetchFn } = setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => json([msgView(11, 'user', '问题')]),
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await screen.findByText('问题')

    await userEvent.click(screen.getByTestId('header-more-menu'))
    await userEvent.click(await screen.findByText('删除当前会话'))
    await userEvent.click(await screen.findByTestId('delete-current-confirm'))

    await waitFor(() =>
      expect(
        fetchFn.mock.calls.some(
          ([u, i]) =>
            String(u).endsWith(`/api/sessions/${SID}`) &&
            (i as RequestInit | undefined)?.method === 'DELETE',
        ),
      ).toBe(true),
    )
    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
  })

  it('批量删除：管理模式勾选两个 → POST batch-delete → 列表移除 + 当前被删回空态', async () => {
    const SID2 = '223e4567-e89b-12d3-a456-426614174001'
    const { fetchFn } = setupApp({
      initialSessions: [
        sessionSummary(),
        sessionSummary({ sessionId: SID2, title: '第二个会话' }),
      ],
      messagesResponse: () => json([msgView(11, 'user', '问题')]),
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await screen.findByText('问题')

    await userEvent.click(screen.getByTestId('sidebar-batch-toggle'))
    await userEvent.click(screen.getByTestId(`batch-check-${SID}`))
    await userEvent.click(screen.getByTestId(`batch-check-${SID2}`))
    expect(screen.getByTestId('sidebar-batch-count')).toHaveTextContent('已选 2 项')

    await userEvent.click(screen.getByTestId('batch-delete-btn'))
    await userEvent.click(await screen.findByTestId('batch-delete-confirm'))

    await waitFor(() =>
      expect(
        fetchFn.mock.calls.some(([u, i]) => {
          if (!String(u).includes('/api/sessions/batch-delete')) return false
          const body = JSON.parse(String((i as RequestInit | undefined)?.body)) as { ids: string[] }
          return body.ids.includes(SID) && body.ids.includes(SID2)
        }),
      ).toBe(true),
    )
    // 两项从列表移除；当前会话（SID）被删 → 主区回空态
    await waitFor(() => expect(screen.queryByTestId(`session-item-${SID}`)).toBeNull())
    expect(screen.queryByTestId(`session-item-${SID2}`)).toBeNull()
    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
  })

  it('流式中操作按钮不渲染（NFR-5）；轮次结束后库 id 对齐回填、按钮出现', async () => {
    let historyCalls = 0
    const { sse, fetchFn } = setupApp({
      initialSessions: [sessionSummary()],
      messagesResponse: () => {
        historyCalls += 1
        if (historyCalls === 1) return json([]) // 选中会话：空历史
        // 轮次结束后对齐：返回持久化的本轮（id 101/102）
        return json([msgView(101, 'user', '你好'), msgView(102, 'assistant', '你好呀')])
      },
    })
    render(<App />)
    await screen.findByTestId(`session-item-${SID}`)
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    await waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/messages'))).toBe(true),
    )

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '你好' } })
    await userEvent.click(screen.getByTestId('chat-send'))
    await screen.findByText('你好')
    // 流式中：无操作按钮（父级不传处理器）
    expect(screen.queryByTestId('msg-actions-101')).toBeNull()

    await act(async () => {
      sse.push(encodeSse.chunk('你好呀'))
      sse.push(encodeSse.done())
    })
    // 轮次完成 → 静默 GET 历史 → 库 id 对齐 → 本轮用户消息出现操作按钮
    expect(await screen.findByTestId('msg-actions-101')).toBeInTheDocument()
  })
})
