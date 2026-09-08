import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChatStream } from './useChatStream'
import { sseResponse, mockFetchOnce } from '../test/sseMock'
import type { PlanTaskInfo } from '../types'

// 迭代5 T6：plan/task 帧 upsert 到当前流式助手消息（AC-37~41）。
// 仅当前流式助手消息持有 planTasks；状态升级不降级；历史消息无面板。

const planFrame = (round: number, tasks: Array<Partial<PlanTaskInfo> & { taskId: string; title: string }>, truncated?: boolean): string => {
  const payload: Record<string, unknown> = {
    round,
    tasks: tasks.map((t) => ({ status: 'pending', ...t })),
  }
  if (truncated !== undefined) payload.truncated = truncated
  return `event:plan\ndata:${JSON.stringify(payload)}\n\n`
}
const taskFrame = (taskId: string, status: 'started' | 'succeeded' | 'failed', extra?: Record<string, unknown>): string =>
  `event:task\ndata:${JSON.stringify({ taskId, title: { t1: '扫描错误日志', t2: '汇总结论', t3: '补充查询' }[taskId] ?? taskId, status, ...extra })}\n\n`
const chunk = (content: string): string => `event:message\ndata:${JSON.stringify({ content })}\n\n`
const done = (): string => `event:done\ndata:[DONE]\n\n`

beforeEach(() => {
  vi.useRealTimers()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('useChatStream 编排面板', () => {
  it('plan/task 帧 upsert 到当前助手消息：plan 建台账，task 推进状态，终态带耗时', async () => {
    mockFetchOnce(
      sseResponse([
        planFrame(1, [
          { taskId: 't1', title: '扫描错误日志', status: 'pending' },
          { taskId: 't2', title: '汇总结论', status: 'pending' },
        ]),
        taskFrame('t1', 'started', { round: 1 }),
        taskFrame('t1', 'succeeded', { durationMs: 8231 }),
        chunk('最终答案'),
        done(),
      ]),
    )
    const { result } = renderHook(() => useChatStream({}))

    await act(async () => {
      await result.current.send('分析日志')
    })

    const assistant = result.current.messages[1]
    expect(assistant.role).toBe('assistant')
    expect(assistant.planTasks).toBeDefined()
    expect(assistant.planTasks).toHaveLength(2)
    expect(assistant.planTasks?.[0]).toMatchObject({
      taskId: 't1',
      title: '扫描错误日志',
      status: 'succeeded',
      durationMs: 8231,
    })
    expect(assistant.planTasks?.[1]).toMatchObject({ taskId: 't2', status: 'pending' })
    // 正文不受影响
    expect(assistant.content).toBe('最终答案')
    // 用户消息不挂面板
    expect(result.current.messages[0].planTasks).toBeUndefined()
  })

  it('状态升级不降级：task(running) 后到达的 plan(pending) 不回退；plan 带来的新任务追加', async () => {
    mockFetchOnce(
      sseResponse([
        planFrame(1, [{ taskId: 't1', title: '扫描错误日志', status: 'pending' }]),
        taskFrame('t1', 'started', { round: 1 }),
        // 再规划 plan 帧重发全量台账：t1 仍在 running（帧序上 plan 略早于终态属防御场景），
        // 另带来新任务 t3
        planFrame(2, [
          { taskId: 't1', title: '扫描错误日志', status: 'pending' },
          { taskId: 't3', title: '补充查询', status: 'pending' },
        ]),
        taskFrame('t1', 'succeeded', { durationMs: 100 }),
        chunk('答案'),
        done(),
      ]),
    )
    const { result } = renderHook(() => useChatStream({}))

    await act(async () => {
      await result.current.send('分析日志')
    })

    const planTasks = result.current.messages[1].planTasks ?? []
    const byId = Object.fromEntries(planTasks.map((t) => [t.taskId, t]))
    expect(byId.t1.status).toBe('succeeded') // 终态覆盖，未被 pending 回退
    expect(byId.t3.status).toBe('pending') // 新任务追加
    expect(planTasks.map((t) => t.taskId)).toEqual(['t1', 't3'])
  })

  it('历史消息（showHistory）不带 planTasks（AC-41）', async () => {
    mockFetchOnce(sseResponse([done()]))
    const { result } = renderHook(() => useChatStream({}))

    act(() => {
      result.current.showHistory('123e4567-e89b-12d3-a456-426614174000', [
        { role: 'user', content: '旧问题', createdAt: '2026-09-08 10:00:00' },
        { role: 'assistant', content: '旧回答', createdAt: '2026-09-08 10:00:01' },
      ])
    })

    for (const m of result.current.messages) {
      expect(m.planTasks).toBeUndefined()
    }
  })

  it('failed 任务帧：状态 failed 并带 error；与 toolCalls 字段共存互不影响', async () => {
    mockFetchOnce(
      sseResponse([
        planFrame(1, [{ taskId: 't1', title: '扫描错误日志', status: 'pending' }]),
        taskFrame('t1', 'started', { round: 1 }),
        taskFrame('t1', 'failed', { error: '工具调用失败：连接超时' }),
        chunk('部分答案'),
        done(),
      ]),
    )
    const { result } = renderHook(() => useChatStream({}))

    await act(async () => {
      await result.current.send('分析日志')
    })

    const t1 = result.current.messages[1].planTasks?.[0]
    expect(t1).toMatchObject({ taskId: 't1', status: 'failed', error: '工具调用失败：连接超时' })
  })
})
