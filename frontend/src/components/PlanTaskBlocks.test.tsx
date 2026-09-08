import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import PlanTaskBlocks from './PlanTaskBlocks'
import type { PlanTaskInfo } from '../types'

// 迭代5 T6：规划与执行面板（antd Steps 竖向，AC-37/AC-38/AC-39）。
// 五状态渲染；空数组/null → 不渲染；与工具块同气泡共存由 MessageBubble 测试覆盖。

const task = (overrides: Partial<PlanTaskInfo>): PlanTaskInfo => ({
  taskId: 't1',
  title: '扫描错误日志',
  status: 'pending',
  ...overrides,
})

describe('PlanTaskBlocks', () => {
  it('无 planTasks 或空数组 → 返回 null（历史消息/普通对话无面板）', () => {
    const { container: c1 } = render(<PlanTaskBlocks planTasks={[]} />)
    expect(c1.firstChild).toBeNull()
    // @ts-expect-error 显式 null 容错
    const { container: c2 } = render(<PlanTaskBlocks planTasks={null} />)
    expect(c2.firstChild).toBeNull()
  })

  it('渲染容器标题与全部任务标题，data-testid=plan-task-blocks', () => {
    render(
      <PlanTaskBlocks
        planTasks={[task({ taskId: 't1', title: '扫描错误日志' }), task({ taskId: 't2', title: '汇总结论' })]}
      />,
    )
    expect(screen.getByTestId('plan-task-blocks')).toBeTruthy()
    expect(screen.getByText('📋 规划与执行')).toBeTruthy()
    expect(screen.getByText('扫描错误日志')).toBeTruthy()
    expect(screen.getByText('汇总结论')).toBeTruthy()
  })

  it('running：显示执行中 Spin 提示；pending：等待态', () => {
    render(<PlanTaskBlocks planTasks={[task({ status: 'pending' }), task({ taskId: 't2', status: 'running' })]} />)
    expect(screen.getByText('执行中…')).toBeTruthy()
  })

  it('succeeded：显示耗时；failed：显示错误摘要', () => {
    render(
      <PlanTaskBlocks
        planTasks={[
          task({ status: 'succeeded', durationMs: 8231 }),
          task({ taskId: 't2', status: 'failed', error: '工具调用失败：连接超时' }),
        ]}
      />,
    )
    expect(screen.getByText(/8231\s*ms/)).toBeTruthy()
    expect(screen.getByText('工具调用失败：连接超时')).toBeTruthy()
  })

  it('skipped：显示已跳过标记', () => {
    render(<PlanTaskBlocks planTasks={[task({ status: 'skipped' })]} />)
    expect(screen.getByText('已跳过')).toBeTruthy()
  })

  it('同 taskId 原地更新不产生重复行（upsert 后数组仍单行）', () => {
    const { rerender } = render(<PlanTaskBlocks planTasks={[task({ status: 'running' })]} />)
    rerender(<PlanTaskBlocks planTasks={[task({ status: 'succeeded', durationMs: 100 })]} />)
    expect(screen.getAllByText('扫描错误日志')).toHaveLength(1)
    expect(screen.getByText(/100\s*ms/)).toBeTruthy()
  })
})
