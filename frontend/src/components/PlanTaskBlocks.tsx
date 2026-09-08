import { Steps, Spin, Tag } from 'antd'
import type { PlanTaskInfo } from '../types'

/**
 * 规划与执行面板（迭代5，T6）：助手气泡内、工具调用折叠块上方。
 * antd Steps 竖向小尺寸展示 Planner 台账：pending 等待 / running Spin 执行中 /
 * succeeded 绿色完成+耗时 / failed 红色错误摘要 / skipped 灰色已跳过。
 * 纯展示（无折叠状态）；数据由 useChatStream 按 taskId upsert，原地更新不重复。
 * 无 planTasks 或空数组 → 返回 null（普通对话/历史消息无面板，AC-41）。
 */
export default function PlanTaskBlocks({ planTasks }: { planTasks: PlanTaskInfo[] }) {
  if (!planTasks || planTasks.length === 0) return null

  const items = planTasks.map((t) => {
    const description = (() => {
      if (t.status === 'running') {
        return (
          <span className="plan-task-running">
            <Spin size="small" /> 执行中…
          </span>
        )
      }
      if (t.status === 'succeeded') {
        return t.durationMs != null ? <span className="plan-task-duration">{t.durationMs}ms</span> : undefined
      }
      if (t.status === 'failed' && t.error) {
        return <span className="plan-task-error" data-testid="plan-task-error">{t.error}</span>
      }
      if (t.status === 'skipped') {
        return (
          <Tag className="plan-task-tag" data-testid="plan-task-skipped">
            已跳过
          </Tag>
        )
      }
      return undefined
    })()

    const stepStatus =
      t.status === 'running'
        ? 'process'
        : t.status === 'succeeded'
          ? 'finish'
          : t.status === 'failed'
            ? 'error'
            : 'wait'

    return {
      key: t.taskId,
      title: (
        <span className="plan-task-title" data-testid="plan-task" data-task={t.taskId} data-status={t.status}>
          {t.title}
        </span>
      ),
      description,
      status: stepStatus as 'wait' | 'process' | 'finish' | 'error',
    }
  })

  return (
    <div className="plan-task-blocks" data-testid="plan-task-blocks">
      <div className="plan-task-header">📋 规划与执行</div>
      <Steps size="small" direction="vertical" items={items} />
    </div>
  )
}
