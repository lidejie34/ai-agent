import { useEffect, useState } from 'react'
import { Alert, Button, Drawer, Form, Input, InputNumber, Modal, Radio, Space, Spin, Switch, message } from 'antd'
import { createTool, getTool, patchTool } from '../api/adminApi'
import { adminErrorText } from '../../utils/errors'
import { hasErrorCode } from '../utils'
import type { HandlerType, ToolUpsertBody } from '../types'

// 新建/编辑工具抽屉（迭代 H，AC-27~35、D10）。
// - 前端预校验不通过不发请求（AC-28）；新建 POST 全量，空数字字段不传。
// - 编辑先 GET 预填、name 禁用；保存先 Modal.confirm；PATCH 全字段且不含 name（AC-32/33/D10）。
// - 400 BAD_REQUEST：抽屉保持打开，后端 message 原文上屏（AC-30）。
// - 503 TOOLS_UNAVAILABLE：已落库但快照刷新失败语义文案（AC-34）。

export interface ToolFormDrawerProps {
  open: boolean
  mode: 'create' | 'edit'
  toolId: number | null
  onClose: () => void
  onSaved: () => void
}

interface FormValues {
  name: string
  description: string
  handlerType: HandlerType
  bean?: string
  script?: string
  inputSchema: string
  guideMd?: string
  enabled: boolean
  timeoutMs?: number | null
  outputMaxChars?: number | null
}

const schemaValidator = async (_rule: unknown, val: string) => {
  if (!val || !val.trim()) throw new Error('请输入输入模式 JSON')
  let obj: unknown
  try {
    obj = JSON.parse(val)
  } catch {
    throw new Error('输入模式需为合法 JSON')
  }
  if (typeof obj !== 'object' || obj === null || Array.isArray(obj) || (!('type' in obj) && !('properties' in (obj as Record<string, unknown>)))) {
    throw new Error('输入模式需为 JSON 对象，且包含 type 或 properties 字段')
  }
}

function confirmEdit(): Promise<boolean> {
  return new Promise((resolve) => {
    Modal.confirm({
      title: '确认保存工具修改？',
      content: '保存后工具注册表立即生效（运行时快照即时刷新），错误配置可能导致该工具调用失败。',
      okText: '确认保存',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}

export default function ToolFormDrawer({ open, mode, toolId, onClose, onSaved }: ToolFormDrawerProps) {
  const [form] = Form.useForm<FormValues>()
  const [submitting, setSubmitting] = useState(false)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const handlerType = Form.useWatch('handlerType', form) ?? 'BUILTIN'

  // 编辑态：打开时 GET 预填（AC-32）
  useEffect(() => {
    if (!open) return
    setFormError(null)
    if (mode !== 'edit' || toolId === null) return
    let alive = true
    setLoadingDetail(true)
    getTool(toolId)
      .then((d) => {
        if (!alive) return
        form.setFieldsValue({
          name: d.name,
          description: d.description,
          handlerType: d.handlerType,
          bean: (d.handlerConfig?.bean as string | undefined) ?? '',
          script: (d.handlerConfig?.script as string | undefined) ?? '',
          inputSchema: d.inputSchema ? JSON.stringify(d.inputSchema, null, 2) : '',
          guideMd: d.guideMd ?? '',
          enabled: d.enabled,
          timeoutMs: d.timeoutMs ?? undefined,
          outputMaxChars: d.outputMaxChars ?? undefined,
        })
      })
      .catch((e) => {
        if (!alive) return
        if (hasErrorCode(e) && e.code === 'TOOL_NOT_FOUND') {
          message.warning('工具不存在或已被删除')
          onSaved()
        } else {
          message.error(hasErrorCode(e) ? adminErrorText(e) : '详情加载失败，请重试')
          onClose()
        }
      })
      .finally(() => {
        if (alive) setLoadingDetail(false)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, mode, toolId])

  const handleSubmitError = (e: unknown) => {
    if (hasErrorCode(e)) {
      if (e.code === 'TOOL_NOT_FOUND') {
        message.warning('工具不存在或已被删除')
        onSaved()
        return
      }
      if (e.code === 'TOOLS_UNAVAILABLE') {
        // 已落库但运行时快照刷新失败（AC-34）
        message.warning('修改已保存到数据库，但运行时快照刷新失败，请刷新列表确认后重试')
        onSaved()
        return
      }
      // 400 校验失败：后端 message 原文上屏（AC-30）
      setFormError(e.code === 'BAD_REQUEST' && e.message?.trim() ? e.message : adminErrorText(e))
    } else {
      setFormError('网络连接中断，请检查网络后重试')
    }
  }

  const onFinish = async (v: FormValues) => {
    setFormError(null)
    setSubmitting(true)
    try {
      const parsed: ToolUpsertBody = {
        description: v.description.trim(),
        inputSchema: JSON.parse(v.inputSchema) as Record<string, unknown>,
        handlerType: v.handlerType,
        handlerConfig:
          v.handlerType === 'SCRIPT'
            ? { script: (v.script ?? '').trim() }
            : { bean: (v.bean ?? '').trim() },
        enabled: v.enabled ?? true,
        guideMd: v.guideMd?.trim() ? v.guideMd : undefined,
      }
      if (mode === 'create') {
        const body: ToolUpsertBody = { name: v.name.trim(), ...parsed }
        if (v.timeoutMs != null) body.timeoutMs = v.timeoutMs
        if (v.outputMaxChars != null) body.outputMaxChars = v.outputMaxChars
        await createTool(body)
        message.success('工具已创建')
        onSaved()
      } else {
        // 高危操作先确认（AC-33）
        const ok = await confirmEdit()
        if (!ok) return
        // 编辑 PATCH 携带全字段（含 null 清空）且不含 name（D10）
        const patchBody = {
          ...parsed,
          guideMd: v.guideMd ?? '',
          timeoutMs: v.timeoutMs ?? null,
          outputMaxChars: v.outputMaxChars ?? null,
        }
        await patchTool(toolId as number, patchBody as Partial<ToolUpsertBody>)
        message.success('工具已保存')
        onSaved()
      }
    } catch (e) {
      handleSubmitError(e)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={mode === 'create' ? '新建工具' : '编辑工具'}
      width={720}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={() => form.submit()}>
            保存
          </Button>
        </Space>
      }
    >
      {loadingDetail && (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Spin />
        </div>
      )}
      {!loadingDetail && (
        <Form<FormValues>
          form={form}
          layout="vertical"
          initialValues={{ handlerType: 'BUILTIN', enabled: true }}
          onFinish={(v) => void onFinish(v)}
        >
          {formError && (
            <Alert type="error" showIcon style={{ marginBottom: 16 }} message={formError} />
          )}

          <Form.Item
            label="名称（name）"
            name="name"
            extra="小写字母开头，仅小写字母/数字/下划线，长度 2-64；创建后不可修改"
            rules={[
              { required: true, message: '请输入工具名称' },
              { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '名称需小写字母开头，仅含小写字母/数字/下划线，长度 2-64' },
            ]}
          >
            <Input placeholder="例如 weather_query" disabled={mode === 'edit'} />
          </Form.Item>

          <Form.Item
            label="描述（description）"
            name="description"
            rules={[
              { required: true, whitespace: true, message: '请输入工具描述' },
              { max: 2000, message: '描述不能超过 2000 字符' },
            ]}
          >
            <Input.TextArea rows={3} maxLength={2000} showCount placeholder="工具用途描述，2000 字以内" />
          </Form.Item>

          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="修改影响线上工具行为，请谨慎"
          />

          <Form.Item label="类型（handlerType）" name="handlerType" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="BUILTIN">BUILTIN</Radio>
              <Radio value="SCRIPT">SCRIPT</Radio>
            </Radio.Group>
          </Form.Item>

          {handlerType === 'BUILTIN' && (
            <>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="bean 为后端已注册的内置工具标识，需与后端 Bean 名称一致，否则保存将被后端拒绝"
              />
              <Form.Item
                label="内置 Bean（handlerConfig.bean）"
                name="bean"
                rules={[{ required: true, whitespace: true, message: '请输入 bean 标识' }]}
              >
                <Input placeholder="例如 weatherTool" />
              </Form.Item>
            </>
          )}

          {handlerType === 'SCRIPT' && (
            <>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="仅填写脚本目录中的文件名（不含路径），文件需已部署存在，否则保存将被后端拒绝"
              />
              <Form.Item
                label="脚本文件名（handlerConfig.script）"
                name="script"
                rules={[{ required: true, whitespace: true, message: '请输入脚本文件名' }]}
              >
                <Input placeholder="例如 weather.groovy（仅文件名）" />
              </Form.Item>
            </>
          )}

          <Form.Item
            label="输入模式（inputSchema，JSON）"
            name="inputSchema"
            rules={[{ required: true, validator: schemaValidator }]}
          >
            <Input.TextArea rows={8} className="admin-mono" placeholder='{"type":"object","properties":{...}}' />
          </Form.Item>

          <Form.Item label="使用指南（guideMd，可空）" name="guideMd">
            <Input.TextArea rows={6} placeholder="可选，Markdown 使用指南，留空表示无指南" />
          </Form.Item>

          <Form.Item
            label="超时时间（ms）"
            name="timeoutMs"
            extra="范围 1-60000，留空使用默认 30000"
            rules={[{ type: 'number', min: 1, max: 60000, message: '超时时间范围为 1-60000（ms）' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} max={60000} placeholder="默认 30000" />
          </Form.Item>

          <Form.Item
            label="输出上限（字符）"
            name="outputMaxChars"
            extra="范围 100-100000，留空使用默认 8000"
            rules={[{ type: 'number', min: 100, max: 100000, message: '输出上限范围为 100-100000（字符）' }]}
          >
            <InputNumber style={{ width: '100%' }} min={100} max={100000} placeholder="默认 8000" />
          </Form.Item>

          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      )}
    </Drawer>
  )
}
