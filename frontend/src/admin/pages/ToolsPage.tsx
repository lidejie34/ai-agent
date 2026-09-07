import { useState } from 'react'
import { Alert, Button, Popconfirm, Skeleton, Space, Switch, Table, Tag, Tooltip, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { deleteTool, listTools, patchTool } from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import { fmtTime, hasErrorCode } from '../utils'
import { HandlerTypeTag } from '../components/tags'
import ToolDetailDrawer from '../components/ToolDetailDrawer'
import ToolFormDrawer from '../components/ToolFormDrawer'
import type { HandlerType, ToolListItem } from '../types'

// 工具注册表（迭代 H，AC-21~26、AC-36/37）：列表 + 启停（乐观/回滚）+ 详情 + 删除；表单见 T4。
export default function ToolsPage() {
  const { data, loading, error, refresh, setData } = useAdminAsync(() => listTools(), [])
  const tools = data ?? []

  const [pendingId, setPendingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [formMode, setFormMode] = useState<'create' | 'edit'>('create')
  const [formId, setFormId] = useState<number | null>(null)

  /** 启停：乐观更新；失败回滚，不整表刷新（AC-22）。 */
  const toggleEnabled = async (row: ToolListItem, next: boolean) => {
    setPendingId(row.id)
    setData((prev) =>
      prev ? prev.map((t) => (t.id === row.id ? { ...t, enabled: next } : t)) : prev,
    )
    try {
      await patchTool(row.id, { enabled: next })
      message.success(next ? '工具已启用' : '工具已停用')
    } catch (e) {
      // 回滚
      setData((prev) =>
        prev ? prev.map((t) => (t.id === row.id ? { ...t, enabled: !next } : t)) : prev,
      )
      message.error(hasErrorCode(e) ? adminErrorText(e) : '操作失败，请稍后重试')
    } finally {
      setPendingId(null)
    }
  }

  const doDelete = async (id: number) => {
    setDeletingId(id)
    try {
      await deleteTool(id)
      message.success('工具已删除')
      await refresh()
    } catch (e) {
      if (hasErrorCode(e) && e.code === 'TOOL_NOT_FOUND') {
        message.warning('工具已被删除')
        await refresh()
      } else {
        message.error(hasErrorCode(e) ? adminErrorText(e) : '删除失败，请稍后重试')
      }
    } finally {
      setDeletingId(null)
    }
  }

  const openCreate = () => {
    setFormMode('create')
    setFormId(null)
    setFormOpen(true)
  }
  const openEdit = (id: number) => {
    setFormMode('edit')
    setFormId(id)
    setFormOpen(true)
  }

  const columns: ColumnsType<ToolListItem> = [
    { title: 'ID', dataIndex: 'id', width: 64 },
    { title: '名称', dataIndex: 'name' },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: { showTitle: false },
      render: (v: string) => (
        <Tooltip title={v}>
          <span className="admin-cell-ellipsis">{v}</span>
        </Tooltip>
      ),
    },
    {
      title: '类型',
      dataIndex: 'handlerType',
      render: (t: HandlerType) => <HandlerTypeTag type={t} />,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 72,
      render: (v: boolean, row) => (
        <Switch
          checked={v}
          loading={pendingId === row.id}
          disabled={pendingId !== null && pendingId !== row.id}
          onChange={(next) => void toggleEnabled(row, next)}
        />
      ),
    },
    {
      title: '超时(ms)',
      dataIndex: 'timeoutMs',
      width: 100,
      render: (v: number | null) => (v === null ? <span className="admin-text-empty">默认 30000</span> : v),
    },
    {
      title: '输出上限',
      dataIndex: 'outputMaxChars',
      width: 100,
      render: (v: number | null) => (v === null ? <span className="admin-text-empty">默认 8000</span> : v),
    },
    {
      title: '指南',
      dataIndex: 'guideLength',
      width: 100,
      render: (n: number) =>
        n === 0 ? <Tag>无指南</Tag> : <Tag color="blue">指南 {n} 字</Tag>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 170,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" onClick={() => { setDetailId(row.id); setDetailOpen(true) }}>
            详情
          </Button>
          <Button size="small" onClick={() => openEdit(row.id)}>
            编辑
          </Button>
          <Popconfirm
            title={
              <span>
                将物理删除工具 <b>{row.name}</b>；删除后模型无法再调用该工具；历史审计日志保留；操作不可恢复。是否继续？
              </span>
            }
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: deletingId === row.id }}
            onConfirm={() => doDelete(row.id)}
          >
            <Button size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div className="tools-page" data-testid="admin-page-tools">
      <div className="admin-page-toolbar">
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建工具
          </Button>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()} data-testid="tools-refresh">
            刷新
          </Button>
        </Space>
      </div>

      {loading && <Skeleton active paragraph={{ rows: 6 }} />}

      {!loading && error && (
        <Alert
          type="error"
          showIcon
          message="工具列表加载失败"
          description={adminErrorText(error)}
          action={
            <Button size="small" onClick={() => void refresh()}>
              重试
            </Button>
          }
        />
      )}

      {!loading && !error && (
        <Table
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={tools}
          pagination={false}
        />
      )}

      <ToolDetailDrawer
        open={detailOpen}
        toolId={detailId}
        onClose={() => setDetailOpen(false)}
        onNotFound={() => {
          setDetailOpen(false)
          void refresh()
        }}
      />
      <ToolFormDrawer
        open={formOpen}
        mode={formMode}
        toolId={formId}
        onClose={() => setFormOpen(false)}
        onSaved={() => {
          setFormOpen(false)
          void refresh()
        }}
      />
    </div>
  )
}
