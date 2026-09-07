import { Tag } from 'antd'

// 展示用小 Tag（迭代 H）：颜色映射集中管理；未知值兜底灰色（antd 默认）。

const HANDLER_COLORS: Record<string, string> = {
  BUILTIN: 'blue',
  SCRIPT: 'purple',
  MCP: 'cyan',
}

export function HandlerTypeTag({ type }: { type: string }) {
  return <Tag color={HANDLER_COLORS[type]}>{type}</Tag>
}

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  TIMEOUT: 'orange',
}

export function LogStatusTag({ status }: { status: string }) {
  return <Tag color={STATUS_COLORS[status]}>{status}</Tag>
}
