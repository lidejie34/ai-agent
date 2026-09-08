import type { ApiError, PlanTaskInfo, PlanTaskStatus, SseFrame, ToolCallInfo, ToolCallStatus } from '../types'

// SSE 分帧纯函数（无 IO，可单测）。以 \n\n 分事件块（兼容 \r\n\r\n），
// 解析 event:/data: 行、注释帧（:开头）、多 data 行按 \n 拼接；
// event:done 的 data 是字面量 [DONE]，不 JSON.parse（AC-20/FR-11.2）。

/**
 * 解析一个完整事件块（不含分隔空行）为一帧；注释块 → comment；无法识别 → null。
 */
export function parseSseBlock(block: string): SseFrame | null {
  const lines = block.split(/\r?\n/)
  let event = 'message'
  const dataLines: string[] = []
  let isComment = false
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '')
    if (line.startsWith(':')) {
      isComment = true // :keepalive 等注释帧
      continue
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (line.startsWith('data:')) {
      let d = line.slice(5)
      if (d.startsWith(' ')) d = d.slice(1) // SSE 规范：去掉单个前导空格
      dataLines.push(d)
    }
  }
  if (isComment && dataLines.length === 0) {
    return { kind: 'comment' }
  }
  const data = dataLines.join('\n') // 多 data 行按 \n 拼接
  switch (event) {
    case 'session':
      return { kind: 'session', sessionId: (JSON.parse(data) as { sessionId: string }).sessionId }
    case 'message':
      return { kind: 'chunk', content: (JSON.parse(data) as { content: string }).content }
    case 'done':
      return { kind: 'done' } // data 为字面量 [DONE]，不解析
    case 'error':
      return { kind: 'error', error: JSON.parse(data) as ApiError }
    case 'tool':
      return parseToolFrame(data)
    case 'plan':
      return parsePlanFrame(data)
    case 'task':
      return parseTaskFrame(data)
    default:
      return null // 未知 event（旧后端/新事件）天然忽略（AC-66 兼容）
  }
}

/** 计划台账五态（event:plan tasks[].status）。 */
const PLAN_TASK_STATUSES: ReadonlySet<string> = new Set([
  'pending',
  'running',
  'succeeded',
  'failed',
  'skipped',
])

/** 解析 event:plan 帧；round 非数、tasks 缺/非数组、任务字段缺失或 status 非法 → null。 */
function parsePlanFrame(data: string): SseFrame | null {
  let obj: Record<string, unknown>
  try {
    obj = JSON.parse(data) as Record<string, unknown>
  } catch {
    return null
  }
  const { round, tasks, truncated } = obj
  if (typeof round !== 'number' || !Array.isArray(tasks) || tasks.length === 0) return null
  const parsed: PlanTaskInfo[] = []
  for (const raw of tasks) {
    const t = raw as Record<string, unknown>
    if (typeof t?.taskId !== 'string' || typeof t?.title !== 'string') return null
    if (typeof t.status !== 'string' || !PLAN_TASK_STATUSES.has(t.status)) return null
    const info: PlanTaskInfo = { taskId: t.taskId, title: t.title, status: t.status as PlanTaskStatus }
    parsed.push(info)
  }
  const frame: Extract<SseFrame, { kind: 'plan' }> = { kind: 'plan', round, tasks: parsed }
  if (truncated === true) frame.truncated = true
  return frame
}

/** 解析 event:task 帧；started → 界面 running 态；字段缺失/status 非法 → null。 */
function parseTaskFrame(data: string): SseFrame | null {
  let obj: Record<string, unknown>
  try {
    obj = JSON.parse(data) as Record<string, unknown>
  } catch {
    return null
  }
  const { taskId, title, status, round, durationMs, error } = obj
  if (typeof taskId !== 'string' || typeof title !== 'string') return null
  if (status !== 'started' && status !== 'succeeded' && status !== 'failed') return null
  const info: PlanTaskInfo = {
    taskId,
    title,
    // 帧上 started 是执行瞬间事件；面板台账状态为 running（Steps process 态）
    status: status === 'started' ? 'running' : (status as PlanTaskStatus),
  }
  if (typeof round === 'number') info.round = round
  if (typeof durationMs === 'number') info.durationMs = durationMs
  if (typeof error === 'string') info.error = error
  return { kind: 'task', info }
}

/** 解析 event:tool 帧；字段缺失或 status 非法 → null（不炸分帧循环）。 */
function parseToolFrame(data: string): SseFrame | null {
  let obj: Record<string, unknown>
  try {
    obj = JSON.parse(data) as Record<string, unknown>
  } catch {
    return null
  }
  const { callId, tool, arguments: args, status, durationMs, error } = obj
  if (typeof callId !== 'string' || typeof tool !== 'string') return null
  if (status !== 'started' && status !== 'succeeded' && status !== 'failed') return null
  const info: ToolCallInfo = {
    callId,
    tool,
    arguments: typeof args === 'string' ? args : '',
    status: status as ToolCallStatus,
  }
  if (typeof durationMs === 'number') info.durationMs = durationMs
  if (typeof error === 'string') info.error = error
  return { kind: 'tool', info }
}

/**
 * 从累计 buffer 中切出完整事件块（\n\n，兼容 \r\n\r\n），返回已解析帧与未完成尾巴。
 */
export function drainFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const blocks = buffer.split(/\r?\n\r?\n/)
  const rest = blocks.pop() ?? '' // 最后一段可能是不完整事件
  const frames: SseFrame[] = []
  for (const b of blocks) {
    if (!b.trim()) continue
    const f = parseSseBlock(b)
    if (f) frames.push(f)
  }
  return { frames, rest }
}
