import { describe, it, expect } from 'vitest'
import { parseSseBlock, drainFrames } from './sseFrames'

// 迭代5 T6：event:plan / event:task 编排帧解析（AC-35/AC-37）。
// 坏 JSON / 字段缺失 / 非法 status → null（不炸分帧循环）；未知 event 仍忽略。

describe('parseSseBlock 编排帧', () => {
  it('event:plan 解析 round/tasks（五态之一），truncated 可选', () => {
    const payload = {
      round: 1,
      tasks: [
        { taskId: 't1', title: '扫描错误日志', status: 'pending' },
        { taskId: 't2', title: '汇总结论', status: 'pending' },
      ],
      truncated: true,
    }
    const f = parseSseBlock(`event:plan\ndata:${JSON.stringify(payload)}`)
    expect(f).toEqual({ kind: 'plan', round: 1, tasks: payload.tasks, truncated: true })
  })

  it('event:plan 无 truncated 键时为 undefined（fastjson2 省键）', () => {
    const payload = { round: 2, tasks: [{ taskId: 't1', title: '任务', status: 'succeeded' }] }
    const f = parseSseBlock(`event:plan\ndata:${JSON.stringify(payload)}`)
    expect(f).toEqual({ kind: 'plan', round: 2, tasks: payload.tasks })
    expect((f as { truncated?: boolean }).truncated).toBeUndefined()
  })

  it('event:task started 帧 → status 映射为 running 并带 round', () => {
    const payload = { taskId: 't1', title: '扫描错误日志', status: 'started', round: 1 }
    const f = parseSseBlock(`event:task\ndata:${JSON.stringify(payload)}`)
    expect(f).toEqual({
      kind: 'task',
      info: { taskId: 't1', title: '扫描错误日志', status: 'running', round: 1 },
    })
  })

  it('event:task succeeded 带 durationMs、failed 带 error（终态无 round）', () => {
    const ok = parseSseBlock(
      `event:task\ndata:${JSON.stringify({ taskId: 't1', title: 't', status: 'succeeded', durationMs: 8231 })}`,
    )
    expect(ok).toEqual({
      kind: 'task',
      info: { taskId: 't1', title: 't', status: 'succeeded', durationMs: 8231 },
    })

    const bad = parseSseBlock(
      `event:task\ndata:${JSON.stringify({ taskId: 't2', title: 't2', status: 'failed', error: '工具调用失败：连接超时' })}`,
    )
    expect(bad).toEqual({
      kind: 'task',
      info: { taskId: 't2', title: 't2', status: 'failed', error: '工具调用失败：连接超时' },
    })
  })

  it('plan/task 帧坏 JSON、字段缺失、非法 status → null', () => {
    expect(parseSseBlock('event:plan\ndata:not-json')).toBeNull()
    expect(parseSseBlock('event:plan\ndata:{"round":1}')).toBeNull() // 缺 tasks
    expect(
      parseSseBlock('event:plan\ndata:{"round":1,"tasks":[{"taskId":"t1","title":"x","status":"weird"}]}'),
    ).toBeNull() // 非法任务状态
    expect(parseSseBlock('event:task\ndata:{"title":"t","status":"started"}')).toBeNull() // 缺 taskId
    expect(parseSseBlock('event:task\ndata:{"taskId":"t1","title":"t","status":"done"}')).toBeNull() // 非法状态
  })

  it('drainFrames 混合编排帧/消息帧/注释全部切出，未知 event 忽略', () => {
    const buffer = [
      `event:plan\ndata:${JSON.stringify({ round: 1, tasks: [{ taskId: 't1', title: '任务', status: 'pending' }] })}\n\n`,
      ':keepalive\n\n',
      'event:future\ndata:{"x":1}\n\n',
      'event:message\ndata:{"content":"答案"}\n\n',
    ].join('')
    const { frames, rest } = drainFrames(buffer)
    expect(frames.map((f) => f.kind)).toEqual(['plan', 'comment', 'chunk'])
    expect(rest).toBe('')
  })
})
