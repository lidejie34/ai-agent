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
})
