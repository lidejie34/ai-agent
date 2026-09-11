// 管理控制台契约类型（迭代 H）。与后端 DTO 对齐；fastjson2 null 省键，可空字段一律可选，展示层兜底「-」。
// 时间字段线网格式 yyyy-MM-dd HH:mm:ss（LocalDateTime 无 T）。

// ---- MCP（GET /api/admin/mcp/servers → { servers: McpServer[] }）----
export type McpStatus = 'READY' | 'UNAVAILABLE'

export interface McpTool {
  name: string
  rawName: string
  description?: string // fastjson2 省键兜底
}

export interface McpServer {
  name: string
  command?: string | null // null/缺键 → 展示「-」
  args?: string[] // 缺键按 [] 处理
  status: McpStatus
  toolCount: number
  tools?: McpTool[] // UNAVAILABLE 为空/缺键
  lastError?: string
  connectedAt?: string // UNAVAILABLE 缺键
}

export interface McpServersResponse {
  servers: McpServer[]
}

// ---- 工具注册表 ----
export type HandlerType = 'BUILTIN' | 'SCRIPT' // 表单/列表仅二值
export type LogHandlerType = 'BUILTIN' | 'SCRIPT' | 'MCP' // 审计过滤三值
export type LogStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT'

export interface ToolListItem {
  id: number
  name: string
  description: string
  handlerType: HandlerType
  enabled: boolean
  timeoutMs: number | null
  outputMaxChars: number | null
  guideLength: number
  createdAt: string
  updatedAt: string
}

export interface ToolDetail {
  id: number
  name: string
  description: string
  inputSchema: Record<string, unknown> | null
  handlerType: HandlerType
  handlerConfig: Record<string, unknown> | null
  guideMd?: string
  enabled: boolean
  timeoutMs: number | null
  outputMaxChars: number | null
  createdAt: string
  updatedAt: string
}

/** 新建 POST body（name 必填）；编辑 PATCH body 不含 name（D10） */
export interface ToolUpsertBody {
  name?: string
  description: string
  inputSchema: Record<string, unknown>
  handlerType: HandlerType
  handlerConfig: Record<string, unknown>
  guideMd?: string
  enabled?: boolean
  timeoutMs?: number
  outputMaxChars?: number
}

// ---- 审计 ----
export interface ToolCallLog {
  id: number
  callId: string
  toolName: string
  handlerType: string // 展示用 Tag，未知值兜底灰色
  sessionId?: string
  inputSummary?: string
  status: LogStatus | string
  durationMs?: number | null
  errorMessage?: string
  resultChars?: number | null
  createdAt: string
}

export interface PageResult<T> {
  content: T[]
  total: number
  page: number
  size: number
}

export interface ToolLogQuery {
  page: number // 0 基
  size: number
  toolName?: string
  sessionId?: string
  status?: LogStatus
  handlerType?: LogHandlerType
  from?: string // yyyy-MM-dd HH:mm:ss
  to?: string
}

// ---- 知识库（迭代6，/api/admin/kb）----
export type KbDocStatus = 'READY' | 'FAILED' // 同步处理，无中间态

export interface KbDocument {
  id: number
  fileName: string
  sizeBytes: number
  chunkCount: number
  status: KbDocStatus | string
  error?: string | null
  createdAt: string
  updatedAt: string
}

export interface KbHealth {
  enabled: boolean
  ollamaOk: boolean
  pgOk: boolean
  documentCount: number
  chunkCount: number
  dimensions: number
}
