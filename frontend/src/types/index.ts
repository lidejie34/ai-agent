// 前后端契约类型（迭代4）。时间字段为后端 fastjson2 输出的字符串（"yyyy-MM-dd HH:mm:ss"），
// 前端用 dayjs 宽解析，不手拆字符串。

/** 统一错误结构（同步响应体 / SSE error 帧 data 共用）。 */
export interface ApiError {
  code: string
  message: string
  timestamp: string
}

export type ChatRole = 'user' | 'assistant'

/** 界面消息；流式助手消息 content 逐帧累积。 */
export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt?: string
  status?: MessageStatus
  /** 流失败时挂在助手消息上（服务端 ApiError 或网络/看门狗 NetworkError） */
  error?: ApiError | NetworkError
}

export type MessageStatus = 'done' | 'streaming' | 'stopped' | 'error'

/** SSE 解析产物（判别联合）。 */
export type SseFrame =
  | { kind: 'session'; sessionId: string }
  | { kind: 'chunk'; content: string }
  | { kind: 'done' }
  | { kind: 'error'; error: ApiError }
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
