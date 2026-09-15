import { useState } from 'react'
import { Alert, Button, Input, Modal, Popconfirm, Skeleton, Space, Table, Tag, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createDimProject,
  deleteDimProject,
  deleteDimTag,
  listDimProjects,
  listDimTags,
  renameDimTag,
  updateDimProject,
} from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import { fmtTime, hasErrorCode } from '../utils'
import type { DimProject, DimTagView } from '../types'

// 维度维护页（迭代10 追加）：
// - 项目：受管 CRUD——必须先在此创建，知识库上传/编辑/过滤与聊天选择器全部下拉引用；
//   改名联动更新引用文档；有文档引用的项目禁删（后端 409 KB_PROJECT_IN_USE 透传）；
// - 标签：无独立表，派生自文档 tags 数组；改名/删除均联动重写引用文档；
// - 命名/校验口径与后端 KbMetaValidator 一致（服务端为权威，400/409 message 透传）。

/** 与后端 KbMetaValidator 同口径白名单（中文/字母/数字/中划线/下划线）。 */
const NAME_PATTERN = /^[一-龥A-Za-z0-9_-]+$/

/** 项目名前端预检（服务端 400 为权威）；返回 null 表示通过。 */
export function validateProjectName(name: string): string | null {
  if (!name) return '项目名不能为空'
  if (name.length > 64 || !NAME_PATTERN.test(name)) {
    return '项目名仅支持中文/字母/数字/中划线/下划线，不超过 64 字符'
  }
  return null
}

/** 标签名前端预检（单个，≤32 字符）。 */
export function validateTagName(name: string): string | null {
  if (!name) return '标签名不能为空'
  if (name.length > 32 || !NAME_PATTERN.test(name)) {
    return '标签仅支持中文/字母/数字/中划线/下划线，不超过 32 字符'
  }
  return null
}

export default function DimPage() {
  const projectsReq = useAdminAsync<DimProject[]>(() => listDimProjects(), [])
  const tagsReq = useAdminAsync<DimTagView[]>(() => listDimTags(), [])
  const projects = projectsReq.data ?? []
  const tags = tagsReq.data ?? []

  const [projectModal, setProjectModal] = useState<{ mode: 'create' } | { mode: 'edit'; row: DimProject } | null>(null)
  const [pName, setPName] = useState('')
  const [pRemark, setPRemark] = useState('')
  const [savingProject, setSavingProject] = useState(false)
  const [deletingProjectId, setDeletingProjectId] = useState<number | null>(null)

  const [renamingTag, setRenamingTag] = useState<DimTagView | null>(null)
  const [tagTo, setTagTo] = useState('')
  const [savingTag, setSavingTag] = useState(false)
  const [deletingTag, setDeletingTag] = useState<string | null>(null)

  const refreshAll = () =>
    Promise.all([void projectsReq.refresh(), void tagsReq.refresh()]).then(() => undefined)

  const openCreateProject = () => {
    setPName('')
    setPRemark('')
    setProjectModal({ mode: 'create' })
  }

  const openEditProject = (row: DimProject) => {
    setPName(row.name)
    setPRemark(row.remark ?? '')
    setProjectModal({ mode: 'edit', row })
  }

  const doSaveProject = async () => {
    if (!projectModal) return
    const name = pName.trim()
    const err = validateProjectName(name)
    if (err) {
      message.error(err)
      return
    }
    const remark = pRemark.trim() || null
    setSavingProject(true)
    try {
      if (projectModal.mode === 'create') {
        await createDimProject({ name, remark })
        message.success(`项目「${name}」已创建`)
      } else {
        await updateDimProject(projectModal.row.id, { name, remark })
        message.success(
          projectModal.row.name === name
            ? `项目「${name}」已更新`
            : `项目「${projectModal.row.name}」已改名为「${name}」，引用文档已联动更新`,
        )
      }
      setProjectModal(null)
      await refreshAll()
    } catch (e) {
      message.error(hasErrorCode(e) ? adminErrorText(e) : '保存失败，请稍后重试')
    } finally {
      setSavingProject(false)
    }
  }

  const doDeleteProject = async (row: DimProject) => {
    setDeletingProjectId(row.id)
    try {
      await deleteDimProject(row.id)
      message.success(`项目「${row.name}」已删除`)
      await refreshAll()
    } catch (e) {
      if (hasErrorCode(e) && e.code === 'KB_PROJECT_IN_USE') {
        // 引用中禁删：透传后端 message（含引用数）
        message.warning(adminErrorText(e))
      } else {
        message.error(hasErrorCode(e) ? adminErrorText(e) : '删除失败，请稍后重试')
      }
    } finally {
      setDeletingProjectId(null)
    }
  }

  const openRenameTag = (row: DimTagView) => {
    setTagTo(row.name)
    setRenamingTag(row)
  }

  const doRenameTag = async () => {
    if (!renamingTag) return
    const to = tagTo.trim()
    const err = validateTagName(to)
    if (err) {
      message.error(err)
      return
    }
    setSavingTag(true)
    try {
      const res = await renameDimTag(renamingTag.name, to)
      message.success(`标签「${renamingTag.name}」已改名为「${to}」，联动 ${res.affectedDocs} 篇文档`)
      setRenamingTag(null)
      await refreshAll()
    } catch (e) {
      message.error(hasErrorCode(e) ? adminErrorText(e) : '改名失败，请稍后重试')
    } finally {
      setSavingTag(false)
    }
  }

  const doDeleteTag = async (row: DimTagView) => {
    setDeletingTag(row.name)
    try {
      const res = await deleteDimTag(row.name)
      message.success(`标签「${row.name}」已从 ${res.affectedDocs} 篇文档移除`)
      await refreshAll()
    } catch (e) {
      message.error(hasErrorCode(e) ? adminErrorText(e) : '删除失败，请稍后重试')
    } finally {
      setDeletingTag(null)
    }
  }

  const projectColumns: ColumnsType<DimProject> = [
    { title: 'ID', dataIndex: 'id', width: 64 },
    { title: '名称', dataIndex: 'name', render: (v: string) => <Tag color="blue">{v}</Tag> },
    {
      title: '备注',
      dataIndex: 'remark',
      render: (v?: string | null) => (v ? v : <span className="admin-text-empty">-</span>),
    },
    { title: '文档数', dataIndex: 'docCount', width: 90 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEditProject(row)} data-testid={`dim-project-edit-${row.id}`}>
            编辑
          </Button>
          <Popconfirm
            title={
              <span>
                将删除项目 <b>{row.name}</b>。有文档引用的项目无法删除，删除前请先调整文档归属。
              </span>
            }
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: deletingProjectId === row.id }}
            onConfirm={() => doDeleteProject(row)}
          >
            <Button size="small" danger data-testid={`dim-project-delete-${row.id}`}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const tagColumns: ColumnsType<DimTagView> = [
    { title: '标签', dataIndex: 'name', render: (v: string) => <Tag>{v}</Tag> },
    { title: '文档数', dataIndex: 'docCount', width: 90 },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" onClick={() => openRenameTag(row)} data-testid={`dim-tag-rename-${row.name}`}>
            改名
          </Button>
          <Popconfirm
            title={
              <span>
                将从所有文档移除标签 <b>{row.name}</b>（当前 {row.docCount} 篇引用），操作不可恢复。是否继续？
              </span>
            }
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: deletingTag === row.name }}
            onConfirm={() => doDeleteTag(row)}
          >
            <Button size="small" danger data-testid={`dim-tag-delete-${row.name}`}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div className="dim-page" data-testid="admin-page-dim">
      <div className="admin-page-toolbar">
        <Space wrap>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateProject} data-testid="dim-project-create">
            新建项目
          </Button>
          <Button
            icon={<ReloadOutlined />}
            loading={projectsReq.loading || tagsReq.loading}
            onClick={() => void refreshAll()}
            data-testid="dim-refresh"
          >
            刷新
          </Button>
        </Space>
        <span className="kb-upload-hint">
          项目在此统一维护：知识库打标、过滤与对话选择器都只能引用此处的项目；标签在上传文档时自由创建
        </span>
      </div>

      <h3 className="dim-section-title">项目</h3>
      {projectsReq.loading && <Skeleton active paragraph={{ rows: 3 }} />}
      {!projectsReq.loading && projectsReq.error && (
        <Alert
          type="error"
          showIcon
          message="项目列表加载失败"
          description={adminErrorText(projectsReq.error)}
          action={
            <Button size="small" onClick={() => void projectsReq.refresh()}>
              重试
            </Button>
          }
        />
      )}
      {!projectsReq.loading && !projectsReq.error && (
        <Table rowKey="id" size="small" columns={projectColumns} dataSource={projects} pagination={false} />
      )}

      <h3 className="dim-section-title">标签</h3>
      {tagsReq.loading && <Skeleton active paragraph={{ rows: 3 }} />}
      {!tagsReq.loading && tagsReq.error && (
        <Alert
          type="error"
          showIcon
          message="标签列表加载失败"
          description={adminErrorText(tagsReq.error)}
          action={
            <Button size="small" onClick={() => void tagsReq.refresh()}>
              重试
            </Button>
          }
        />
      )}
      {!tagsReq.loading && !tagsReq.error && (
        <Table rowKey="name" size="small" columns={tagColumns} dataSource={tags} pagination={false} />
      )}

      <Modal
        title={projectModal?.mode === 'edit' ? `编辑项目：${projectModal.row.name}` : '新建项目'}
        open={projectModal !== null}
        onOk={() => void doSaveProject()}
        onCancel={() => setProjectModal(null)}
        okText="保存"
        cancelText="取消"
        confirmLoading={savingProject}
        destroyOnHidden
        data-testid="dim-project-modal"
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            placeholder="项目名（中文/字母/数字/中划线/下划线，≤64）"
            maxLength={64}
            value={pName}
            onChange={(e) => setPName(e.target.value)}
            data-testid="dim-project-name"
          />
          <Input
            placeholder="备注（可选）"
            maxLength={255}
            value={pRemark}
            onChange={(e) => setPRemark(e.target.value)}
            data-testid="dim-project-remark"
          />
          {projectModal?.mode === 'edit' && projectModal.row.name !== pName.trim() && (
            <span className="kb-upload-hint">改名将联动更新所有引用该项目的文档</span>
          )}
        </Space>
      </Modal>

      <Modal
        title={`标签改名：${renamingTag?.name ?? ''}`}
        open={renamingTag !== null}
        onOk={() => void doRenameTag()}
        onCancel={() => setRenamingTag(null)}
        okText="保存"
        cancelText="取消"
        confirmLoading={savingTag}
        destroyOnHidden
        data-testid="dim-tag-rename-modal"
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            placeholder="新标签名（≤32 字符）"
            maxLength={32}
            value={tagTo}
            onChange={(e) => setTagTo(e.target.value)}
            data-testid="dim-tag-rename-to"
          />
          <span className="kb-upload-hint">
            改名将联动重写 {renamingTag?.docCount ?? 0} 篇引用文档的标签；若目标标签已存在于同一文档将自动去重
          </span>
        </Space>
      </Modal>
    </div>
  )
}
