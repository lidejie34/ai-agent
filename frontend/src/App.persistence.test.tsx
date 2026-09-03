import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import App from './App'
import { controllableSse, encodeSse } from './test/sseMock'
import { SESSION_ID_KEY } from './utils/storage'
import type { ApiError, SessionMessageView, SessionSummary } from './types'

const SID = '123e4567-e89b-12d3-a456-426614174000'

function sessionSummary(): SessionSummary {
  return {
    sessionId: SID,
    title: '历史会话',
    createdAt: '2026-09-01 10:00:00',
    updatedAt: '2026-09-03 14:00:00',
    previewRole: 'user',
    previewText: '历史预览',
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

interface SetupOpts {
  messagesResponse?: () => Response
}

function setupApp(opts: SetupOpts = {}) {
  const sse = controllableSse()
  const fetchFn = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/api/chat/stream')) {
      init?.signal?.addEventListener('abort', () => {
        try {
          sse.error(new DOMException('Aborted', 'AbortError'))
        } catch {
          /* closed */
        }
      })
      return sse.response
    }
    if (url.includes('/messages')) {
      return opts.messagesResponse ? opts.messagesResponse() : json([])
    }
    if (url.includes('/api/sessions')) {
      if (method === 'DELETE') return json({ deleted: true })
      return json([sessionSummary()])
    }
    return new Response('not found', { status: 404 })
  })
  vi.stubGlobal('fetch', fetchFn)
  return { sse, fetchFn }
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  message.destroy()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('App 持久化（T10）', () => {
  it('刷新恢复：localStorage 中有会话 ID 时拉取历史并回填消息区', async () => {
    localStorage.setItem(SESSION_ID_KEY, SID)
    const history: SessionMessageView[] = [
      { role: 'user', content: '上次的问题', createdAt: '2026-09-03 14:00:00' },
      { role: 'assistant', content: '上次的回答', createdAt: '2026-09-03 14:00:01' },
    ]
    setupApp({ messagesResponse: () => json(history) })
    render(<App />)

    expect(await screen.findByText('上次的问题')).toBeInTheDocument()
    expect(await screen.findByText('上次的回答')).toBeInTheDocument()
    // 历史回填后不显示空态
    expect(screen.queryByTestId('empty-state')).toBeNull()
  })

  it('刷新恢复时会话已删除（404）：静默回空态并清除本地记录', async () => {
    localStorage.setItem(SESSION_ID_KEY, SID)
    const notFound: ApiError = {
      code: 'SESSION_NOT_FOUND',
      message: '会话不存在或已被删除',
      timestamp: '',
    }
    setupApp({ messagesResponse: () => json(notFound, 404) })
    render(<App />)

    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
    expect(screen.queryByTestId('inline-error')).toBeNull()
    expect(localStorage.getItem(SESSION_ID_KEY)).toBeNull()
  })

  it('输入草稿在重新挂载后恢复（未发送的内容不丢失）', async () => {
    setupApp()
    const first = render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '写到一半的草稿' } })
    // 草稿已即时落盘
    expect(localStorage.getItem('chat.draft:global')).toBe('写到一半的草稿')

    first.unmount()
    render(<App />)
    expect((await screen.findByTestId('chat-input')) as HTMLTextAreaElement).toHaveValue('写到一半的草稿')
  })

  it('关闭记忆开关后发送：无状态对话不写入会话 ID', async () => {
    const { sse } = setupApp()
    render(<App />)
    await waitFor(() => expect(screen.queryByTestId('sidebar-skeleton')).toBeNull())

    // 先预置一个会话 ID（模拟上次记忆模式），关闭开关后应被清除
    localStorage.setItem(SESSION_ID_KEY, SID)
    await userEvent.click(screen.getByTestId('remember-switch'))
    expect(localStorage.getItem(SESSION_ID_KEY)).toBeNull()

    fireEvent.change(screen.getByTestId('chat-input'), { target: { value: '无状态提问' } })
    await userEvent.click(screen.getByTestId('chat-send'))
    expect(await screen.findByText('无状态提问')).toBeInTheDocument()

    // 无状态流不推送 event:session；storage 中始终无会话 ID
    await act(async () => {
      sse.push(encodeSse.chunk('无状态回答'))
      sse.push(encodeSse.done())
      sse.close()
    })
    expect(localStorage.getItem(SESSION_ID_KEY)).toBeNull()
  })
})
