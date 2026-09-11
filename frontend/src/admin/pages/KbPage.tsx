import { useState } from 'react'
import { Alert, Button, Empty, Popconfirm, Skeleton, Space, Table, Tag, Tooltip, Upload, message } from 'antd'
import type { UploadProps } from 'antd/es/upload/interface'
import { ReloadOutlined, UploadOutlined, WarningOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  deleteKbDocument,
  getKbHealth,
  listKbDocuments,
  reindexKbDocument,
  uploadKbDocument,
} from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import { fmtBytes, fmtTime, hasErrorCode } from '../utils'
import { KbStatusTag } from '../components/tags'
import type { KbDocument, KbHealth } from '../types'

// 知识库管理页（迭代6，#88）：
// - 顶部健康 Alert：Ollama bge-m3 / PG(pgvector) 分项状态 + 文档/切片计数；仅进页与手动刷新时探测，无轮询；
// - 上传：仅 .md/.markdown/.txt（UTF-8），同步切片+向量化，同名文档后端覆盖；
// - 列表：文件名/大小/切片数/状态/错误/更新时间 + 重建索引/删除（级联）。
export default function KbPage() {
  const healthReq = useAdminAsync<KbHealth>(() => getKbHealth(), [])
  const docsReq = useAdminAsync<KbDocument[]>(() => listKbDocuments(), [])
  const docs = docsReq.data ?? []

  const [uploading, setUploading] = useState(false)
  const [reindexingId, setReindexingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)

  const refreshAll = () =>
    Promise.all([void healthReq.refresh(), void docsReq.refresh()]).then(() => undefined)

  const customRequest: UploadProps['customRequest'] = async (option) => {
    const file = option.file as File
    setUploading(true)
    try {
      await uploadKbDocument(file)
      message.success(`「${file.name}」上传并向量化完成；同名文档已覆盖更新`)
      option.onSuccess?.({}, new XMLHttpRequest())
      await refreshAll()
    } catch (e) {
      option.onError?.(e as Error)
      message.error(hasErrorCode(e) ? adminErrorText(e) : '上传失败，请稍后重试')
    } finally {
      setUploading(false)
    }
  }

  const doReindex = async (id: number) => {
    setReindexingId(id)
    try {
      await reindexKbDocument(id)
      message.success('索引重建完成')
      await refreshAll()
    } catch (e) {
      message.error(hasErrorCode(e) ? adminErrorText(e) : '重建索引失败，请稍后重试')
    } finally {
      setReindexingId(null)
    }
  }

  const doDelete = async (id: number) => {
    setDeletingId(id)
    try {
      await deleteKbDocument(id)
      message.success('文档及其切片已删除')
      await refreshAll()
    } catch (e) {
      if (hasErrorCode(e) && e.code === 'KB_NOT_FOUND') {
        message.warning('文档已被删除')
        await refreshAll()
      } else {
        message.error(hasErrorCode(e) ? adminErrorText(e) : '删除失败，请稍后重试')
      }
    } finally {
      setDeletingId(null)
    }
  }

  const columns: ColumnsType<KbDocument> = [
    { title: 'ID', dataIndex: 'id', width: 64 },
    {
      title: '文件名',
      dataIndex: 'fileName',
      render: (v: string) => <span className="admin-cell-ellipsis">{v}</span>,
    },
    { title: '大小', dataIndex: 'sizeBytes', width: 100, render: (n: number) => fmtBytes(n) },
    {
      title: '切片数',
      dataIndex: 'chunkCount',
      width: 80,
      render: (n: number, row) => (row.status === 'FAILED' ? <span className="admin-text-empty">-</span> : n),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: string) => <KbStatusTag status={s} />,
    },
    {
      title: '错误',
      dataIndex: 'error',
      width: 70,
      render: (e?: string | null) =>
        e ? (
          <Tooltip title={e}>
            <WarningOutlined style={{ color: '#ff4d4f' }} data-testid="kb-row-error-icon" />
          </Tooltip>
        ) : (
          <span className="admin-text-empty">-</span>
        ),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, row) => (
        <Space size={4}>
          <Button
            size="small"
            loading={reindexingId === row.id}
            disabled={reindexingId !== null && reindexingId !== row.id}
            onClick={() => void doReindex(row.id)}
          >
            重建索引
          </Button>
          <Popconfirm
            title={
              <span>
                将删除文档 <b>{row.fileName}</b> 及其全部切片，删除后对话不再引用其内容，操作不可恢复。是否继续？
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
    <div className="kb-page" data-testid="admin-page-kb">
      <KbHealthAlert health={healthReq.data} loading={healthReq.loading} error={healthReq.error} />

      <div className="admin-page-toolbar">
        <Space>
          <Upload
            accept=".md,.markdown,.txt"
            showUploadList={false}
            multiple={false}
            customRequest={customRequest}
            data-testid="kb-upload"
          >
            <Button type="primary" icon={<UploadOutlined />} loading={uploading}>
              上传文档
            </Button>
          </Upload>
          <Button
            icon={<ReloadOutlined />}
            loading={docsReq.loading || healthReq.loading}
            onClick={() => void refreshAll()}
            data-testid="kb-refresh"
          >
            刷新
          </Button>
        </Space>
        <span className="kb-upload-hint">仅支持 Markdown / TXT（UTF-8），单文件 ≤ 10MB</span>
      </div>

      {docsReq.loading && <Skeleton active paragraph={{ rows: 6 }} />}

      {!docsReq.loading && docsReq.error && (
        <Alert
          type="error"
          showIcon
          message="文档列表加载失败"
          description={adminErrorText(docsReq.error)}
          action={
            <Button size="small" onClick={() => void docsReq.refresh()}>
              重试
            </Button>
          }
        />
      )}

      {!docsReq.loading && !docsReq.error && docs.length === 0 && (
        <Empty description="知识库暂无文档，请上传 Markdown / TXT 文件" />
      )}

      {!docsReq.loading && !docsReq.error && docs.length > 0 && (
        <Table rowKey="id" size="small" columns={columns} dataSource={docs} pagination={false} />
      )}
    </div>
  )
}

/** 健康 Alert：两下游均正常为 success；任一异常为 warning 并点名；health 请求自身失败为 error。 */
function KbHealthAlert({
  health,
  loading,
  error,
}: {
  health: KbHealth | null
  loading: boolean
  error: { code: string; message?: string } | null
}) {
  if (loading && !health) return null
  if (error) {
    return (
      <Alert
        className="kb-health-alert"
        type="error"
        showIcon
        message="健康检查失败"
        description={adminErrorText(error)}
        style={{ marginBottom: 12 }}
      />
    )
  }
  if (!health) return null

  const allOk = health.ollamaOk && health.pgOk
  return (
    <Alert
      className="kb-health-alert"
      data-testid="kb-health"
      type={allOk ? 'success' : 'warning'}
      showIcon
      style={{ marginBottom: 12 }}
      message={
        allOk
          ? '知识库服务正常'
          : '知识库部分依赖不可用：上传与问答增强将失败或降级'
      }
      description={
        <Space size={16} wrap>
          <Tag color={health.ollamaOk ? 'green' : 'red'} data-testid="kb-health-ollama">
            Ollama bge-m3：{health.ollamaOk ? '正常' : '不可用'}
          </Tag>
          <Tag color={health.pgOk ? 'green' : 'red'} data-testid="kb-health-pg">
            向量库 PG/pgvector：{health.pgOk ? '正常' : '不可用'}
          </Tag>
          <span>文档 {health.documentCount} 个</span>
          <span>切片 {health.chunkCount} 条</span>
          <span>向量维度 {health.dimensions}</span>
        </Space>
      }
    />
  )
}
