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
    if (url.includes('/messages')) {
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
})
