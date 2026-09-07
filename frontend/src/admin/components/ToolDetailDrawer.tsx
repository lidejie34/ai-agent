import { useEffect, useState } from 'react'
import { Descriptions, Drawer, Spin, message } from 'antd'
import { getTool } from '../api/adminApi'
import { adminErrorText } from '../../utils/errors'
import { fmtTime, hasErrorCode } from '../utils'
import { HandlerTypeTag } from './tags'
import JsonBlock from './JsonBlock'
import TextBlock from './TextBlock'
import type { ToolDetail } from '../types'

// 工具全字段只读抽屉（迭代 H，AC-25/26）。404 → 提示 + 关抽屉 + 列表刷新（onNotFound）。

interface ToolDetailDrawerProps {
  open: boolean
  toolId: number | null
  onClose: () => void
  onNotFound: () => void
}

export default function ToolDetailDrawer({ open, toolId, onClose, onNotFound }: ToolDetailDrawerProps) {
  const [detail, setDetail] = useState<ToolDetail | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open || toolId === null) return
    let alive = true
    setDetail(null)
    setLoading(true)
    getTool(toolId)
      .then((d) => {
        if (alive) setDetail(d)
      })
      .catch((e) => {
        if (!alive) return
        if (hasErrorCode(e) && e.code === 'TOOL_NOT_FOUND') {
          message.warning('工具不存在或已被删除')
          onNotFound()
        } else {
          message.error(hasErrorCode(e) ? adminErrorText(e) : '详情加载失败，请重试')
        }
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, toolId])

  return (
    <Drawer width={720} open={open} onClose={onClose} title="工具详情" destroyOnClose>
      {loading && <Spin />}
      {!loading && detail && (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="名称">{detail.name}</Descriptions.Item>
          <Descriptions.Item label="描述">
            <TextBlock text={detail.description} copyable={false} />
          </Descriptions.Item>
          <Descriptions.Item label="类型">
            <HandlerTypeTag type={detail.handlerType} />
          </Descriptions.Item>
          <Descriptions.Item label="处理配置（handlerConfig）">
            <JsonBlock value={detail.handlerConfig} />
          </Descriptions.Item>
          <Descriptions.Item label="输入模式（inputSchema）">
            <JsonBlock value={detail.inputSchema} />
          </Descriptions.Item>
          <Descriptions.Item label="使用指南（guideMd）">
            <TextBlock text={detail.guideMd} empty="无指南" copyable={false} />
          </Descriptions.Item>
          <Descriptions.Item label="状态">{detail.enabled ? '已启用' : '已停用'}</Descriptions.Item>
          <Descriptions.Item label="超时时间（ms）">{detail.timeoutMs ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="输出上限（字符）">{detail.outputMaxChars ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{fmtTime(detail.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{fmtTime(detail.updatedAt)}</Descriptions.Item>
        </Descriptions>
      )}
    </Drawer>
  )
}
