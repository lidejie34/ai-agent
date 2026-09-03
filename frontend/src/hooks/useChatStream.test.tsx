import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useChatStream } from './useChatStream'
import { encodeSse, sseResponse, controllableSse, mockFetchOnce, mockControllableFetch, jsonErrorResponse, lastFetchBody } from '../test/sseMock'
import type { ApiError } from '../types'

const SID = '123e4567-e89b-12d3-a456-426614174000'
const apiError = (code: string, message = 'err'): ApiError => ({
  code,
  message,
  timestamp: '2026-09-03 10:00:00',
})

beforeEach(() => {
  vi.useRealTimers()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('useChatStream 新建会话（AC-17）', () => {
  it('请求体 sessionId:""；先乐观渲染用户消息；event:session 确立 ID；逐帧打字机；done 后空闲', async () => {
    const onChanged = vi.fn()
    const fetchFn = mockFetchOnce(
      sseResponse([encodeSse.session(SID), encodeSse.chunk('你'), encodeSse.chunk('好'), encodeSse.done()]),
    )
    const { result } = renderHook(() => useChatStream({ onSessionsChanged: onChanged }))

    await act(async () => {
      await result.current.send('你好呀')
    })

    // 三态请求体：新建带空串
    expect(lastFetchBody(fetchFn)).toMatchObject({ message: '你好呀', sessionId: '' })

    // 用户消息乐观渲染 + 助手全文
    const msgs = result.current.messages
    expect(msgs[0]).toMatchObject({ role: 'user', content: '你好呀' })
    expect(msgs[1]).toMatchObject({ role: 'assistant', content: '你好', status: 'done' })
    expect(result.current.currentSessionId).toBe(SID)
    expect(result.current.status).toBe('idle')
    expect(result.current.isStreaming).toBe(false)
    // session 确立与 done 各刷新一次侧边栏
    expect(onChanged).toHaveBeenCalled()
  })

  it('空白消息被拦截，不发请求', async () => {
    const fetchFn = mockFetchOnce(sseResponse([encodeSse.done()]))
    const { result } = renderHook(() => useChatStream({}))
    await act(async () => {
      await result.current.send('   ')
    })
    expect(fetchFn).not.toHaveBeenCalled()
    expect(result.current.messages).toHaveLength(0)
  })
})

describe('useChatStream 无状态（AC-18）', () => {
  it('remember=false：请求体省略 sessionId 键；无 session 帧；不确立会话 ID', async () => {
    const fetchFn = mockFetchOnce(sseResponse([encodeSse.chunk('无状态回复'), encodeSse.done()]))
    const { result } = renderHook(() => useChatStream({}))

    act(() => result.current.setRemember(false))
    await act(async () => {
      await result.current.send('在吗')
    })

    const body = lastFetchBody(fetchFn) as Record<string, unknown>
    expect(body).toMatchObject({ message: '在吗' })
    expect(body).not.toHaveProperty('sessionId')
    expect(result.current.currentSessionId).toBeNull()
    expect(result.current.messages[1]).toMatchObject({ content: '无状态回复', status: 'done' })
  })
})

describe('useChatStream 续接（AC-19 请求体半）', () => {
  it('showHistory 后续接发送：请求体带 UUID 且无 history', async () => {
    const fetchFn = mockFetchOnce(sseResponse([encodeSse.chunk('续接回答'), encodeSse.done()]))
    const { result } = renderHook(() => useChatStream({}))

    act(() => {
      result.current.showHistory(SID, [
        { role: 'user', content: '历史问题', createdAt: '2026-09-03 09:00:00' },
        { role: 'assistant', content: '历史回答', createdAt: '2026-09-03 09:00:01' },
      ])
    })
    expect(result.current.messages).toHaveLength(2)
    expect(result.current.currentSessionId).toBe(SID)

    await act(async () => {
      await result.current.send('再问一个')
    })

    const body = lastFetchBody(fetchFn) as Record<string, unknown>
    expect(body).toMatchObject({ message: '再问一个', sessionId: SID })
    expect(body).not.toHaveProperty('history')
  })
})

describe('useChatStream 停止生成（AC-21）', () => {
  it('stop() 触发 abort；片段保留并标记已停止；无错误；输入恢复', async () => {
    const sse = controllableSse()
    const fetchFn = mockControllableFetch(sse)
    const { result } = renderHook(() => useChatStream({}))

    act(() => {
      void result.current.send('讲个故事')
    })
    // 等流建立并推一个片段
    await waitFor(() => expect(fetchFn).toHaveBeenCalled())
    act(() => sse.push(encodeSse.session(SID)))
    act(() => sse.push(encodeSse.chunk('从前')))
    await waitFor(() => expect(result.current.messages[1]?.content).toBe('从前'))

    act(() => result.current.stop())
    await waitFor(() => expect(result.current.status).toBe('idle'))

    const assistant = result.current.messages[1]
    expect(assistant.content).toBe('从前') // 片段保留
    expect(assistant.status).toBe('stopped') // 已停止
    expect(result.current.lastError).toBeNull() // 不算错误
    expect(result.current.isStreaming).toBe(false) // 输入恢复
    // AC-21：fetch 的 abort signal 确实被 abort
    const signal = fetchFn.mock.calls[0][1]?.signal as AbortSignal
    expect(signal.aborted).toBe(true)
  })
})

describe('useChatStream 错误（AC-22）', () => {
  it('event:error（MEMORY_UNAVAILABLE）→ 助手消息 error + lastError', async () => {
    mockFetchOnce(sseResponse([encodeSse.error(apiError('MEMORY_UNAVAILABLE', '会话服务暂不可用，请稍后重试'))]))
    const { result } = renderHook(() => useChatStream({}))
    await act(async () => {
      await result.current.send('hi')
    })
    expect(result.current.messages[1].status).toBe('error')
    expect(result.current.lastError?.code).toBe('MEMORY_UNAVAILABLE')
    expect(result.current.status).toBe('error')
  })

  it('HTTP 503 JSON 错误体 → 走同一错误流程', async () => {
    mockFetchOnce(jsonErrorResponse(apiError('ARK_NOT_CONFIGURED'), 503))
    const { result } = renderHook(() => useChatStream({}))
    await act(async () => {
      await result.current.send('hi')
    })
    expect(result.current.lastError?.code).toBe('ARK_NOT_CONFIGURED')
    expect(result.current.messages[1].status).toBe('error')
  })
})

describe('useChatStream 看门狗（AC-20）', () => {
  it('30s 无任何帧 → 超时错误', async () => {
    vi.useFakeTimers()
    const sse = controllableSse()
    mockControllableFetch(sse)
    const { result } = renderHook(() => useChatStream({}))

    await act(async () => {
      void result.current.send('hi')
      await vi.advanceTimersByTimeAsync(0) // 让 fetch/流建立
    })
    expect(result.current.isStreaming).toBe(true)

    // 超过 30s 无任何帧 → 看门狗 abort（reason 区分超时）→ onError
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000)
    })

    expect(result.current.lastError?.code).toBe('WATCHDOG_TIMEOUT')
    expect(result.current.isStreaming).toBe(false)
  })

  it('20s 内仅有 keepalive 注释帧 → 不超时（注释帧重置看门狗）', async () => {
    vi.useFakeTimers()
    const sse = controllableSse()
    mockControllableFetch(sse)
    const { result } = renderHook(() => useChatStream({}))

    act(() => {
      void result.current.send('hi')
    })
    await vi.advanceTimersByTimeAsync(0)

    // 每 10s 一帧 keepalive，推进到 60s（远超单次 30s 阈值），仍在流式
    for (let t = 10; t <= 50; t += 10) {
      act(() => sse.push(encodeSse.comment()))
      await vi.advanceTimersByTimeAsync(10_000)
    }
    expect(result.current.lastError).toBeNull()
    expect(result.current.isStreaming).toBe(true)
  })
})
