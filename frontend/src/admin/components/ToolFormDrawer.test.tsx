import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Modal, message } from 'antd'
import ToolformDrawer, { type ToolFormDrawerProps } from './ToolFormDrawer'
import { clearAdminToken, persistAdminToken } from '../auth'
import { apiErrorResponse, jsonResponse } from '../testHelpers'
import type { ToolDetail } from '../types'

// T4：工具新建/编辑表单（AC-27~35）
const editDetail: ToolDetail = {
  id: 7,
  name: 'script_tool',
  description: '脚本工具描述',
  inputSchema: { type: 'object', properties: { q: { type: 'string' } } },
  handlerType: 'SCRIPT',
  handlerConfig: { script: 'foo.groovy' },
  guideMd: '指南内容XYZ',
  enabled: false,
  timeoutMs: null,
  outputMaxChars: null,
  createdAt: '2026-09-01 10:00:00',
  updatedAt: '2026-09-02 11:00:00',
}

const createdDetail: ToolDetail = { ...editDetail, id: 9, name: 'new_tool', handlerType: 'BUILTIN', handlerConfig: { bean: 'myBean' }, enabled: true }

beforeEach(() => {
  clearAdminToken()
  persistAdminToken('tok-x')
  message.destroy()
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearAdminToken()
  message.destroy()
  Modal.destroyAll()
  // jsdom 不触发动画结束，静态 confirm 销毁后 modal 节点仍残留（离场态 pointer-events:none），
  // 会跨测试命中隐藏按钮；confirm 每次调用各自建根，移除安全。
  // 注意：.ant-message 为单例 holder，不可移除（新消息将渲染进游离节点）。
  Array.from(document.querySelectorAll('.ant-modal-root')).forEach((n) => n.remove())
})

// message 销毁后离场态 notice 节点在 jsdom 中残留，仅匹配非离场态 notice
const findMessage = async (text: string) =>
  waitFor(() => {
    const notices = Array.from(document.querySelectorAll('.ant-message-notice')).filter(
      (n) => !/leave|hidden/.test(String(n.className)),
    )
    expect(notices.some((n) => n.textContent?.includes(text))).toBe(true)
  })

function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

function renderForm(props: Partial<ToolFormDrawerProps> = {}) {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  const utils = render(
    <ToolformDrawer open mode="create" toolId={null} onClose={onClose} onSaved={onSaved} {...props} />,
  )
  return { onSaved, onClose, ...utils }
}

const nameInput = () => screen.getByPlaceholderText('例如 weather_query')
const descInput = () => screen.getByPlaceholderText('工具用途描述，2000 字以内')
const schemaInput = () => screen.getByPlaceholderText('{"type":"object","properties":{...}}')
const beanInput = () => screen.getByPlaceholderText('例如 weatherTool')
const scriptInput = () => screen.getByPlaceholderText('例如 weather.groovy（仅文件名）')
const saveBtn = () => screen.getByRole('button', { name: /^保\s*存$/ })

// userEvent.type 将 { 视为功能键语法起始，字面 { 需双写转义；} 无需转义
const escKeys = (s: string) => s.replace(/\{/g, '{{')

// antd v5 Modal.confirm 标题同时渲染于 .ant-modal-title 与 .ant-modal-confirm-title，
// 故以容器节点判断确认框是否出现
const waitForConfirm = async () =>
  waitFor(() => {
    const node = document.querySelector('.ant-modal-confirm')
    expect(node).toBeTruthy()
    return node as HTMLElement
  })

async function fillValidCreate(overrides?: { name?: string; schema?: string; bean?: string }) {
  await userEvent.type(nameInput(), overrides?.name ?? 'new_tool')
  await userEvent.type(descInput(), '新工具描述')
  await userEvent.type(schemaInput(), escKeys(overrides?.schema ?? '{"type":"object"}'))
  await userEvent.type(beanInput(), overrides?.bean ?? 'myBean')
}

describe('工具表单抽屉', () => {
  it('新建态字段齐全：默认 BUILTIN、启用开关默认开、name 帮助文案（AC-27）', () => {
    stubFetch(() => jsonResponse({}))
    renderForm()
    expect(screen.getByText('名称（name）')).toBeInTheDocument()
    expect(screen.getByText('描述（description）')).toBeInTheDocument()
    expect(screen.getByText('输入模式（inputSchema，JSON）')).toBeInTheDocument()
    expect(screen.getByText('使用指南（guideMd，可空）')).toBeInTheDocument()
    expect(screen.getByText('超时时间（ms）')).toBeInTheDocument()
    expect(screen.getByText('输出上限（字符）')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'BUILTIN' })).toBeChecked()
    expect(screen.getByRole('switch')).toBeChecked()
    expect(screen.getByText(/小写字母开头，仅小写字母\/数字\/下划线，长度 2-64/)).toBeInTheDocument()
  })

  it('前端预校验：空表单/非法 name/非法 JSON/缺 type&properties 均字段报错且不发请求（AC-28）', async () => {
    const fn = stubFetch(() => jsonResponse(createdDetail, 201))
    renderForm()

    // 空提交：必填报错，不发请求
    await userEvent.click(saveBtn())
    expect(await screen.findByText('请输入工具名称')).toBeInTheDocument()
    expect(fn).not.toHaveBeenCalled()

    // 非法 name
    await userEvent.type(nameInput(), 'X')
    await userEvent.type(descInput(), '新工具描述')
    await userEvent.type(schemaInput(), escKeys('{"type":"object"}'))
    await userEvent.type(beanInput(), 'myBean')
    await userEvent.click(saveBtn())
    expect(await screen.findByText(/名称需小写字母开头/)).toBeInTheDocument()
    expect(fn).not.toHaveBeenCalled()

    // 非法 JSON
    await userEvent.clear(nameInput())
    await userEvent.type(nameInput(), 'new_tool')
    await userEvent.clear(schemaInput())
    await userEvent.type(schemaInput(), 'not-json')
    await userEvent.click(saveBtn())
    expect(await screen.findByText('输入模式需为合法 JSON')).toBeInTheDocument()
    expect(fn).not.toHaveBeenCalled()

    // JSON 合法但无 type/properties
    await userEvent.clear(schemaInput())
    await userEvent.type(schemaInput(), escKeys('{"foo":1}'))
    await userEvent.click(saveBtn())
    expect(await screen.findByText(/包含 type 或 properties/)).toBeInTheDocument()
    expect(fn).not.toHaveBeenCalled()
  })

  it('合法提交发 POST：inputSchema 为对象、handlerConfig 为 {bean} 对象、空数字字段不传（AC-28）', async () => {
    const fn = stubFetch(() => jsonResponse(createdDetail, 201))
    renderForm()
    await fillValidCreate()
    await userEvent.click(saveBtn())

    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/api/admin/tools')
    expect((init as RequestInit).method).toBe('POST')
    const body = JSON.parse((init as RequestInit).body as string)
    expect(body).toMatchObject({
      name: 'new_tool',
      description: '新工具描述',
      handlerType: 'BUILTIN',
      handlerConfig: { bean: 'myBean' },
      enabled: true,
    })
    expect(body.inputSchema).toEqual({ type: 'object' }) // 对象非字符串
    expect(body).not.toHaveProperty('timeoutMs')
    expect(body).not.toHaveProperty('outputMaxChars')
    expect(((init as RequestInit).headers as Record<string, string>)['X-Admin-Token']).toBe('tok-x')
    await findMessage('工具已创建')
  })

  it('201 创建成功：成功 message + onSaved（关单+列表刷新，AC-29）', async () => {
    stubFetch(() => jsonResponse(createdDetail, 201))
    const { onSaved } = renderForm()
    await fillValidCreate()
    await userEvent.click(saveBtn())
    await findMessage('工具已创建')
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
  })

  it('400 BAD_REQUEST：抽屉保持打开、输入保留、后端 message 原文上屏（AC-30）', async () => {
    stubFetch(() => apiErrorResponse('BAD_REQUEST', '工具名(name)已存在: new_tool', 400))
    const { onSaved } = renderForm()
    await fillValidCreate()
    await userEvent.click(saveBtn())
    expect(await screen.findByText('工具名(name)已存在: new_tool')).toBeInTheDocument()
    expect(nameInput()).toHaveValue('new_tool') // 输入保留
    expect(onSaved).not.toHaveBeenCalled() // 未关单
  })

  it('handlerType 联动：切 SCRIPT 显示 script 提示与输入；提交 body handlerConfig={script} 无 bean（AC-31）', async () => {
    const fn = stubFetch(() => jsonResponse({ ...createdDetail, handlerType: 'SCRIPT', handlerConfig: { script: 'x.groovy' } }, 201))
    renderForm()
    await fillValidCreate()

    await userEvent.click(screen.getByRole('radio', { name: 'SCRIPT' }))
    expect(await screen.findByText(/仅填写脚本目录中的文件名（不含路径），文件需已部署存在/)).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('例如 weatherTool')).toBeNull()
    await userEvent.type(scriptInput(), 'x.groovy')

    await userEvent.click(saveBtn())
    await waitFor(() => expect(fn).toHaveBeenCalled())
    const body = JSON.parse((fn.mock.calls[0][1] as RequestInit).body as string)
    expect(body.handlerType).toBe('SCRIPT')
    expect(body.handlerConfig).toEqual({ script: 'x.groovy' })
    expect(body.handlerConfig).not.toHaveProperty('bean')
    await findMessage('工具已创建')
  })

  it('编辑态：先 GET 预填、name 禁用、schema 格式化回填、script 回填（AC-32）', async () => {
    stubFetch((url, init) => {
      if ((init?.method ?? 'GET') === 'GET' && url.includes('/api/admin/tools/7')) return jsonResponse(editDetail)
      return new Response('nf', { status: 404 })
    })
    renderForm({ mode: 'edit', toolId: 7 })

    await waitFor(() => expect(nameInput()).toHaveValue('script_tool'))
    expect(nameInput()).toBeDisabled()
    expect(scriptInput()).toHaveValue('foo.groovy')
    expect((schemaInput() as HTMLTextAreaElement).value).toMatch(/"type": "object"/)
    expect(screen.getByPlaceholderText(/可选/)).toHaveValue('指南内容XYZ')
    expect(screen.getByRole('switch')).not.toBeChecked()
  })

  it('编辑保存弹 Modal.confirm：点取消不发 PATCH（AC-33）', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET' && url.includes('/api/admin/tools/7')) return jsonResponse(editDetail)
      if (method === 'PATCH') return jsonResponse(editDetail)
      return new Response('nf', { status: 404 })
    })
    renderForm({ mode: 'edit', toolId: 7 })
    await waitFor(() => expect(nameInput()).toHaveValue('script_tool'))

    await userEvent.clear(descInput())
    await userEvent.type(descInput(), '改过的描述')
    await userEvent.click(saveBtn())

    const confirm = await waitForConfirm()
    expect(confirm.textContent).toContain('确认保存工具修改？')
    expect(confirm.textContent).toContain('保存后工具注册表立即生效')
    const cancelBtn = document.querySelector('.ant-modal-confirm-btns .ant-btn-default') as HTMLElement
    await userEvent.click(cancelBtn)
    // 取消后 onFinish 提前返回：不发 PATCH
    await waitFor(() =>
      expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'PATCH')).toBe(false),
    )
  })

  it('编辑保存确认后发 PATCH：全字段、无 name 键、不发 POST（AC-33/D10）', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET' && url.includes('/api/admin/tools/7')) return jsonResponse(editDetail)
      if (method === 'PATCH') return jsonResponse(editDetail)
      return new Response('nf', { status: 404 })
    })
    renderForm({ mode: 'edit', toolId: 7 })
    await waitFor(() => expect(nameInput()).toHaveValue('script_tool'))

    await userEvent.clear(descInput())
    await userEvent.type(descInput(), '改过的描述')
    await userEvent.click(saveBtn())
    await waitForConfirm()
    const okBtn = document.querySelector('.ant-modal-confirm-btns .ant-btn-primary') as HTMLElement
    await userEvent.click(okBtn)

    await waitFor(() => expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'PATCH')).toBe(true))
    const patchCall = fn.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'PATCH')!
    expect(patchCall[0]).toContain('/api/admin/tools/7')
    const body = JSON.parse((patchCall[1] as RequestInit).body as string)
    expect(body).not.toHaveProperty('name') // D10：PATCH 不带 name
    expect(body).toMatchObject({
      description: '改过的描述',
      handlerType: 'SCRIPT',
      handlerConfig: { script: 'foo.groovy' },
      enabled: false,
    })
    expect(body.inputSchema).toEqual(editDetail.inputSchema)
    expect(body).toHaveProperty('guideMd')
    expect(body).toHaveProperty('timeoutMs')
    expect(body).toHaveProperty('outputMaxChars')
    expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'POST')).toBe(false)
  })

  it('503 TOOLS_UNAVAILABLE：提示「已保存但快照刷新失败」语义文案并关单刷新（AC-34）', async () => {
    const fn = stubFetch((url, init) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET' && url.includes('/api/admin/tools/7')) return jsonResponse(editDetail)
      if (method === 'PATCH') return apiErrorResponse('TOOLS_UNAVAILABLE', 'refresh failed', 503)
      return new Response('nf', { status: 404 })
    })
    const { onSaved } = renderForm({ mode: 'edit', toolId: 7 })
    await waitFor(() => expect(nameInput()).toHaveValue('script_tool'))
    await userEvent.click(saveBtn())
    await waitForConfirm()
    const okBtn = document.querySelector('.ant-modal-confirm-btns .ant-btn-primary') as HTMLElement
    await userEvent.click(okBtn)

    await findMessage('修改已保存到数据库，但运行时快照刷新失败，请刷新列表确认后重试')
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(fn.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'PATCH')).toBe(true)
  })

  it('高危字段警示 Alert 存在（AC-35）', () => {
    stubFetch(() => jsonResponse({}))
    renderForm()
    expect(screen.getByText('修改影响线上工具行为，请谨慎')).toBeInTheDocument()
  })
})
