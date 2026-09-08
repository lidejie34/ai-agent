// 前后端契约类型（迭代4）。时间字段为后端 fastjson2 输出的字符串（"yyyy-MM-dd HH:mm:ss"），
// 前端用 dayjs 宽解析，不手拆字符串。

/** 统一错误结构（同步响应体 / SSE error 帧 data 共用）。 */
export interface ApiError {
  code: string
  message: string
  timestamp: string
}

export type ChatRole = 'user' | 'assistant'

/** 工具调用状态（插入迭代 G，event:tool 帧）：started→succeeded/failed。 */
export type ToolCallStatus = 'started' | 'succeeded' | 'failed'

/**
 * 一次工具调用的折叠块数据（插入迭代 G）。
 * callId 为幂等键（requestId|tool|sha1），started 与终态帧同 callId upsert 折叠。
 */
export interface ToolCallInfo {
  callId: string
  tool: string
  arguments: string
  status: ToolCallStatus
  durationMs?: number
  error?: string
}

/**
 * 规划子任务状态（迭代5，SDD 编排）。
 * 帧上 started 为执行瞬间事件，界面台账状态为 running；终态 succeeded/failed/skipped。
 */
export type PlanTaskStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'skipped'

/**
 * 规划面板中的一个子任务（迭代5，event:plan/event:task 帧）。
 * taskId 为幂等键：plan 帧全量台账按 taskId upsert，task 帧按 taskId 推进状态。
 */
export interface PlanTaskInfo {
  taskId: string
  title: string
  status: PlanTaskStatus
  /** task started 帧携带（所属 Planner 轮次） */
  round?: number
  /** task succeeded 帧携带（执行耗时毫秒） */
  durationMs?: number
  /** task failed 帧携带（脱敏后失败摘要） */
  error?: string
}

/** 界面消息；流式助手消息 content 逐帧累积。 */
export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt?: string
  status?: MessageStatus
  /** 流失败时挂在助手消息上（服务端 ApiError 或网络/看门狗 NetworkError） */
  error?: ApiError | NetworkError
  /** 仅当轮流式助手消息持有（工具调用折叠块）；历史消息无此字段（AC-68） */
  toolCalls?: ToolCallInfo[]
  /** 仅当轮流式助手消息持有（SDD 规划与执行面板）；历史消息无此字段（迭代5，AC-41） */
  planTasks?: PlanTaskInfo[]
}

export type MessageStatus = 'done' | 'streaming' | 'stopped' | 'error'

/** SSE 解析产物（判别联合）。 */
export type SseFrame =
  | { kind: 'session'; sessionId: string }
  | { kind: 'chunk'; content: string }
  | { kind: 'done' }
  | { kind: 'error'; error: ApiError }
  | { kind: 'tool'; info: ToolCallInfo } // event:tool 工具生命周期帧（插入迭代 G）
  | { kind: 'plan'; round: number; tasks: PlanTaskInfo[]; truncated?: boolean } // event:plan 计划台账帧（迭代5）
  | { kind: 'task'; info: PlanTaskInfo } // event:task 子任务状态帧（迭代5）
  | { kind: 'comment' } // :keepalive 等，仅重置看门狗，不产生界面消息

/** 会话列表项；title/preview 可能为 null（fastjson2 省略键，按可选兜底）。 */
export interface SessionSummary {
  sessionId: string
  title: string | null
  createdAt: string
  updatedAt: string
  previewRole: ChatRole | null
  previewText: string | null
}

/** 历史消息视图（全文、升序）。 */
export interface SessionMessageView {
  role: ChatRole
  content: string
  createdAt: string
}

export type ChatStatus = 'idle' | 'streaming' | 'error'

/** 前端归一化后的流错误（网络层/看门狗），与 ApiError 判别。 */
export interface NetworkError {
  code: 'NETWORK_ERROR' | 'WATCHDOG_TIMEOUT'
  message: string
}

export function isNetworkError(e: ApiError | NetworkError): e is NetworkError {
  return e.code === 'NETWORK_ERROR' || e.code === 'WATCHDOG_TIMEOUT'
}
