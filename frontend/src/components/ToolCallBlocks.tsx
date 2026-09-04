import { useState } from 'react'
import { Collapse, Spin, Tag } from 'antd'
import type { ToolCallInfo } from '../types'

/**
 * 工具调用折叠块（插入迭代 G，T11）：助手气泡内、正文 Markdown 上方。
 * 每个工具一块，按帧到达顺序；started 自动展开显示「执行中」，succeeded 自动折叠，
 * failed 保持展开（错误摘要可见）；用户手动开合的选择保留（userToggled 覆盖自动策略），
 * 同 callId 终态帧原地更新。
 */
export default function ToolCallBlocks({ toolCalls }: { toolCalls: ToolCallInfo[] }) {
  // 用户手动开合的 callId 集合（started 被手动折起 / 终态被手动展开）
  const [userToggled, setUserToggled] = useState<Set<string>>(new Set())

  if (!toolCalls || toolCalls.length === 0) return null

  const isAutoOpen = (t: ToolCallInfo) => t.status !== 'succeeded'

  const activeKey = toolCalls
    .filter((t) => {
      const toggled = userToggled.has(t.callId)
      return isAutoOpen(t) ? !toggled : toggled
    })
    .map((t) => t.callId)

  const onChange = (keys: string | string[]) => {
    const open = new Set(Array.isArray(keys) ? keys : [keys])
    setUserToggled((prev) => {
      const next = new Set(prev)
      for (const t of toolCalls) {
        const isOpen = open.has(t.callId)
        // 用户选择与自动策略不一致 → 记为手动覆盖
        if (isOpen !== isAutoOpen(t)) next.add(t.callId)
        else next.delete(t.callId)
      }
      return next
    })
  }

  const items = toolCalls.map((t) => ({
    key: t.callId,
    label: (
      <span className="tool-call-header" data-testid="tool-call-block" data-tool={t.tool}>
        <span className="tool-call-title">🔧 调用工具 {t.tool}</span>
        <span className="tool-call-status" data-testid="tool-call-status" data-status={t.status}>
          {t.status === 'started' && (
            <span className="tool-call-running">
              <Spin size="small" /> 执行中…
            </span>
          )}
          {t.status === 'succeeded' && (
            <Tag color="success" className="tool-call-tag">
              成功{t.durationMs != null ? ` ${t.durationMs}ms` : ''}
            </Tag>
          )}
          {t.status === 'failed' && (
            <Tag color="error" className="tool-call-tag">
              失败
            </Tag>
          )}
        </span>
      </span>
    ),
    children: (
      <div className="tool-call-body">
        {t.arguments && (
          <div className="tool-call-args">
            <span className="tool-call-args-label">入参：</span>
            <code>{t.arguments}</code>
          </div>
        )}
        {t.status === 'failed' && t.error && (
          <div className="tool-call-error" data-testid="tool-call-error">
            {t.error}
          </div>
        )}
      </div>
    ),
  }))

  return (
    <div className="tool-call-blocks" data-testid="tool-call-blocks">
      <Collapse
        size="small"
        items={items}
        activeKey={activeKey}
        onChange={(keys) => onChange(keys as string | string[])}
      />
    </div>
  )
}
