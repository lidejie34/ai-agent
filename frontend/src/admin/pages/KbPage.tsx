import { useState } from 'react'
import { Alert, Button, Empty, Input, Modal, Popconfirm, Skeleton, Space, Table, Tag, Tooltip, Upload, message } from 'antd'
import { ReloadOutlined, UploadOutlined, WarningOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  deleteKbDocument,
  getKbHealth,
  listKbDocuments,
  patchKbDocumentMeta,
  reindexKbDocument,
  uploadKbDocument,
} from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import { fmtBytes, fmtTime, hasErrorCode } from '../utils'
import { KbStatusTag } from '../components/tags'
import type { KbDocFilter, KbDocument, KbHealth } from '../types'

// 知识库管理页（迭代6，#88；迭代10 维度元数据）：
// - 顶部健康 Alert：Ollama bge-m3 / PG(pgvector) 分项状态 + 文档/切片计数；仅进页与手动刷新时探测，无轮询；
// - 上传：弹窗内选文件（.md/.markdown/.txt UTF-8）+ 可选项目/标签打标，同步切片+向量化，同名文档后端覆盖；
// - 列表：项目/标签两列 + 过滤区（project 等值 + 单标签包含 AND）；编辑标签弹窗 PATCH 全量替换；
// - 重建索引/删除（级联）沿用迭代6；reindex 保留维度元数据。

/** 与后端 KbMetaValidator 同口径白名单：中文/字母/数字/中划线/下划线（逗号显式禁止）。 */
const META_PATTERN = /^[一-龥A-Za-z0-9_-]+$/

/** 逗号分隔输入 → 标签数组（trim/去空白/保序去重；支持中英文逗号）。 */
export function splitTagsInput(s: string): string[] {
  return [...new Set(s.split(/[,，]/).map((t) => t.trim()).filter(Boolean))]
}

/** 轻量前端预检（服务端为权威校验，400 message 透传）；返回 null 表示通过。 */
export function validateMetaInput(project: string, tags: string[]): string | null {
  if (project && (project.length > 64 || !META_PATTERN.test(project))) {
    return '项目名仅支持中文/字母/数字/中划线/下划线，不超过 64 字符'
  }
  if (tags.length > 8) return '标签最多 8 个'
  const bad = tags.find((t) => t.length > 32 || !META_PATTERN.test(t))
  if (bad) return `标签「${bad}」非法：仅支持中文/字母/数字/中划线/下划线，单个不超过 32 字符`
  return null
}

export default function KbPage() {
  const healthReq = useAdminAsync<KbHealth>(() => getKbHealth(), [])
  // 过滤：草稿（输入框）与生效值（点击查询后）分离，避免每击键一次请求
  const [filterDraft, setFilterDraft] = useState({ project: '', tag: '' })
  const [filter, setFilter] = useState<KbDocFilter>({})
  const docsReq = useAdminAsync<KbDocument[]>(() => listKbDocuments(filter), [filter])
  const docs = docsReq.data ?? []

  const [uploadOpen, setUploadOpen] = useState(false)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [upProject, setUpProject] = useState('')
  const [upTags, setUpTags] = useState('')
  const [uploading, setUploading] = useState(false)

  const [editing, setEditing] = useState<KbDocument | null>(null)
  const [editProject, setEditProject] = useState('')
  const [editTags, setEditTags] = useState('')
  const [savingMeta, setSavingMeta] = useState(false)

  const [reindexingId, setReindexingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)

  const refreshAll = () =>
    Promise.all([void healthReq.refresh(), void docsReq.refresh()]).then(() => undefined)

  const applyFilter = () => {
    const project = filterDraft.project.trim()
    const tag = filterDraft.tag.trim()
    const err = validateMetaInput(project, tag ? [tag] : [])
    if (err) {
      message.error(err)
      return
    }
    setFilter({ project: project || undefined, tag: tag || undefined })
  }

  const resetFilter = () => {
    setFilterDraft({ project: '', tag: '' })
    setFilter({})
  }

  const openUpload = () => {
    setPendingFile(null)
    setUpProject('')
    setUpTags('')
    setUploadOpen(true)
  }

  const doUpload = async () => {
    if (!pendingFile) {
      message.warning('请先选择要上传的文件')
      return
    }
    const project = upProject.trim()
    const tags = splitTagsInput(upTags)
    const err = validateMetaInput(project, tags)
    if (err) {
      message.error(err)
      return
    }
    setUploading(true)
    try {
      await uploadKbDocument(pendingFile, { project, tags })
      message.success(`「${pendingFile.name}」上传并向量化完成；同名文档已覆盖更新`)
      setUploadOpen(false)
      await refreshAll()
    } catch (e) {
      message.error(hasErrorCode(e) ? adminErrorText(e) : '上传失败，请稍后重试')
    } finally {
      setUploading(false)
    }
  }

  const openEditMeta = (row: KbDocument) => {
    setEditing(row)
    setEditProject(row.project ?? '')
    setEditTags((row.tags ?? []).join(','))
  }

  const doSaveMeta = async () => {
    if (!editing) return
    const project = editProject.trim()
    const tags = splitTagsInput(editTags)
    const err = validateMetaInput(project, tags)
    if (err) {
      message.error(err)
      return
    }
    setSavingMeta(true)
    try {
      // PATCH 全量替换语义：空项目 → null 清除，空标签 → [] 清空
      await patchKbDocumentMeta(editing.id, { project: project || null, tags })
      message.success(`「${editing.fileName}」的项目/标签已更新`)
      setEditing(null)
      await docsReq.refresh()
    } catch (e) {
      if (hasErrorCode(e) && e.code === 'KB_NOT_FOUND') {
        message.warning('文档已被删除')
        setEditing(null)
        await docsReq.refresh()
      } else {
        message.error(hasErrorCode(e) ? adminErrorText(e) : '更新失败，请稍后重试')
      }
    } finally {
      setSavingMeta(false)
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
    {
      title: '项目',
      dataIndex: 'project',
      width: 110,
      render: (v?: string) => (v ? <Tag color="blue">{v}</Tag> : <span className="admin-text-empty">-</span>),
    },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 180,
      render: (tags?: string[]) =>
        tags && tags.length > 0 ? (
          <Space size={4} wrap>
            {tags.map((t) => (
              <Tag key={t}>{t}</Tag>
            ))}
          </Space>
        ) : (
          <span className="admin-text-empty">-</span>
        ),
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
      width: 240,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEditMeta(row)}>
            编辑标签
          </Button>
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
        <Space wrap>
          <Button
            type="primary"
            icon={<UploadOutlined />}
            onClick={openUpload}
            data-testid="kb-upload"
          >
            上传文档
          </Button>
          <Button
            icon={<ReloadOutlined />}
            loading={docsReq.loading || healthReq.loading}
            onClick={() => void refreshAll()}
            data-testid="kb-refresh"
          >
            刷新
          </Button>
          <Input
            allowClear
            placeholder="按项目过滤"
            style={{ width: 150 }}
            value={filterDraft.project}
            onChange={(e) => setFilterDraft((d) => ({ ...d, project: e.target.value }))}
            onPressEnter={applyFilter}
            data-testid="kb-filter-project"
          />
          <Input
            allowClear
            placeholder="按标签过滤（单个）"
            style={{ width: 170 }}
            value={filterDraft.tag}
            onChange={(e) => setFilterDraft((d) => ({ ...d, tag: e.target.value }))}
            onPressEnter={applyFilter}
            data-testid="kb-filter-tag"
          />
          <Button onClick={applyFilter} data-testid="kb-filter-apply">
            查询
          </Button>
          <Button onClick={resetFilter} data-testid="kb-filter-reset">
            重置
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
        <Empty
          description={
            filter.project || filter.tag
              ? '没有匹配过滤条件的文档，可调整项目/标签后重新查询'
              : '知识库暂无文档，请上传 Markdown / TXT 文件'
          }
        />
      )}

      {!docsReq.loading && !docsReq.error && docs.length > 0 && (
        <Table rowKey="id" size="small" columns={columns} dataSource={docs} pagination={false} />
      )}

      <Modal
        title="上传文档"
        open={uploadOpen}
        onOk={() => void doUpload()}
        onCancel={() => setUploadOpen(false)}
        okText="上传"
        cancelText="取消"
        confirmLoading={uploading}
        destroyOnHidden
        data-testid="kb-upload-modal"
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Upload
            accept=".md,.markdown,.txt"
            showUploadList={false}
            multiple={false}
            beforeUpload={(file) => {
              setPendingFile(file)
              return false // 本地暂存，点「上传」随 meta 一起提交
            }}
            data-testid="kb-upload-file"
          >
            <Button icon={<UploadOutlined />}>选择文件</Button>
          </Upload>
          <span className="kb-upload-hint" data-testid="kb-upload-filename">
            {pendingFile ? `已选择：${pendingFile.name}` : '仅支持 .md/.markdown/.txt（UTF-8），≤ 10MB'}
          </span>
          <Input
            placeholder="归属项目（可选），如：订单域"
            maxLength={64}
            value={upProject}
            onChange={(e) => setUpProject(e.target.value)}
            data-testid="kb-upload-project"
          />
          <Input
            placeholder="标签（可选，逗号分隔，最多 8 个），如：售后,退货"
            value={upTags}
            onChange={(e) => setUpTags(e.target.value)}
            data-testid="kb-upload-tags"
          />
        </Space>
      </Modal>

      <Modal
        title={`编辑项目/标签：${editing?.fileName ?? ''}`}
        open={editing !== null}
        onOk={() => void doSaveMeta()}
        onCancel={() => setEditing(null)}
        okText="保存"
        cancelText="取消"
        confirmLoading={savingMeta}
        destroyOnHidden
        data-testid="kb-meta-modal"
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            placeholder="归属项目（留空 = 清除归属）"
            maxLength={64}
            value={editProject}
            onChange={(e) => setEditProject(e.target.value)}
            data-testid="kb-meta-project"
          />
          <Input
            placeholder="标签（逗号分隔，留空 = 清空标签）"
            value={editTags}
            onChange={(e) => setEditTags(e.target.value)}
            data-testid="kb-meta-tags"
          />
          <span className="kb-upload-hint">
            保存为全量替换：项目/标签仅支持中文、字母、数字、中划线、下划线；标签最多 8 个、单个 ≤ 32 字符
          </span>
        </Space>
      </Modal>
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
