import { useState } from 'react'
import { Alert, Button, Checkbox, Empty, Input, Modal, Popconfirm, Skeleton } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { SessionSummary } from '../types'
import { validateRenameTitle } from '../utils/title'

export interface SessionSidebarProps {
  sessions: SessionSummary[]
  loading: boolean
  loadError: boolean
  currentSessionId: string | null
  onSelect: (id: string) => void
  onNew: () => void
  onDelete: (id: string) => Promise<void> | void
  onRename: (id: string, title: string) => Promise<void> | void
  onRetry: () => void
  /** 迭代13 FR-3：批量删除所选会话 */
  onBatchDelete: (ids: string[]) => Promise<void> | void
  /** 流式中禁止进入批量管理模式（NFR-5） */
  batchDisabled?: boolean
}

function formatTime(ts: string | null | undefined): string {
  if (!ts) return ''
  const d = dayjs(ts)
  return d.isValid() ? (d.isSame(dayjs(), 'day') ? d.format('HH:mm') : d.format('M月D日')) : ''
}

export default function SessionSidebar({
  sessions,
  loading,
  loadError,
  currentSessionId,
  onSelect,
  onNew,
  onDelete,
  onRename,
  onRetry,
  onBatchDelete,
  batchDisabled,
}: SessionSidebarProps) {
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [renameError, setRenameError] = useState<string | null>(null)
  // 迭代13 FR-3：批量管理模式——勾选多项后一次删除；模式下点击项切换勾选而非打开会话
  const [batchMode, setBatchMode] = useState(false)
  const [checkedIds, setCheckedIds] = useState<Set<string>>(new Set())

  const exitBatchMode = () => {
    setBatchMode(false)
    setCheckedIds(new Set())
  }

  const toggleChecked = (id: string) => {
    setCheckedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  const openRename = (s: SessionSummary) => {
    setRenamingId(s.sessionId)
    setRenameValue(s.title ?? '')
    setRenameError(null)
  }

  const submitRename = async () => {
    if (!renamingId) return
    const v = validateRenameTitle(renameValue)
    if (!v.ok) {
      setRenameError(v.error)
      return
    }
    await onRename(renamingId, v.title)
    setRenamingId(null)
  }

  return (
    <div className="session-sidebar" data-testid="session-sidebar">
      <Button
        type="primary"
        block
        icon={<PlusOutlined />}
        onClick={onNew}
        data-testid="new-session-btn"
      >
        新会话
      </Button>

      {!loading && !loadError && sessions.length > 0 && (
        <Button
          type="text"
          size="small"
          block
          className="mt-2"
          disabled={batchDisabled}
          onClick={() => (batchMode ? exitBatchMode() : setBatchMode(true))}
          data-testid="sidebar-batch-toggle"
        >
          {batchMode ? '退出批量管理' : '批量管理'}
        </Button>
      )}

      {loading && (
        <div data-testid="sidebar-skeleton" className="mt-3">
          <Skeleton active paragraph={{ rows: 4 }} />
        </div>
      )}

      {!loading && loadError && (
        <Alert
          className="mt-3"
          type="error"
          showIcon
          message="会话列表加载失败"
          action={
            <Button size="small" onClick={onRetry} data-testid="sidebar-retry-btn">
              重试
            </Button>
          }
        />
      )}

      {!loading && !loadError && sessions.length === 0 && (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" className="mt-3" />
      )}

      {!loading && !loadError && sessions.length > 0 && (
        <ul className="session-list" data-testid="session-list">
          {sessions.map((s) => {
            const active = s.sessionId === currentSessionId
            return (
              <li
                key={s.sessionId}
                className={`session-item${active ? ' active' : ''}`}
                data-testid={`session-item-${s.sessionId}`}
                data-active={active || undefined}
              >
                {batchMode && (
                  <Checkbox
                    checked={checkedIds.has(s.sessionId)}
                    onChange={() => toggleChecked(s.sessionId)}
                    data-testid={`batch-check-${s.sessionId}`}
                    aria-label="选择会话"
                  />
                )}
                <button
                  type="button"
                  className="session-item-main"
                  onClick={() => (batchMode ? toggleChecked(s.sessionId) : onSelect(s.sessionId))}
                  data-testid={`session-select-${s.sessionId}`}
                >
                  <span className="session-item-title">{s.title ?? '新会话'}</span>
                  <span className="session-item-time">{formatTime(s.updatedAt)}</span>
                  {s.previewText && <span className="session-item-preview">{s.previewText}</span>}
                </button>
                {!batchMode && (
                <span className="session-item-actions">
                  <Button
                    type="text"
                    size="small"
                    aria-label="重命名"
                    data-testid={`rename-btn-${s.sessionId}`}
                    icon={<EditOutlined />}
                    onClick={() => openRename(s)}
                  />
                  <Popconfirm
                    title="确定删除该会话及其全部消息？"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true, 'data-testid': `delete-confirm-${s.sessionId}` } as never}
                    cancelButtonProps={{ 'data-testid': `delete-cancel-${s.sessionId}` } as never}
                    onConfirm={() => onDelete(s.sessionId)}
                  >
                    <Button
                      type="text"
                      size="small"
                      danger
                      aria-label="删除"
                      data-testid={`delete-btn-${s.sessionId}`}
                      icon={<DeleteOutlined />}
                    />
                  </Popconfirm>
                </span>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {batchMode && (
        <div className="sidebar-batch-bar" data-testid="sidebar-batch-bar">
          <span className="sidebar-batch-count" data-testid="sidebar-batch-count">
            已选 {checkedIds.size} 项
          </span>
          <Popconfirm
            title={`确定删除所选 ${checkedIds.size} 个会话？`}
            description="将删除会话及其全部消息，不可恢复"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true, 'data-testid': 'batch-delete-confirm' } as never}
            onConfirm={() => {
              void onBatchDelete([...checkedIds])
              exitBatchMode()
            }}
          >
            <Button
              size="small"
              danger
              disabled={checkedIds.size === 0}
              data-testid="batch-delete-btn"
            >
              删除所选
            </Button>
          </Popconfirm>
        </div>
      )}

      <Modal
        title="重命名会话"
        open={renamingId !== null}
        onOk={submitRename}
        onCancel={() => setRenamingId(null)}
        okText="保存"
        cancelText="取消"
        okButtonProps={{ 'data-testid': 'rename-ok' } as never}
      >
        <Input
          autoFocus
          value={renameValue}
          maxLength={240}
          onChange={(e) => {
            setRenameValue(e.target.value)
            setRenameError(null)
          }}
          placeholder="输入新标题"
          data-testid="rename-input"
        />
        {renameError && (
          <Alert className="mt-2" type="error" showIcon message={renameError} data-testid="rename-error" />
        )}
      </Modal>
    </div>
  )
}
