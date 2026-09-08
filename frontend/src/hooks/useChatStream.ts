import { useCallback, useRef, useState } from 'react'
import { streamChat } from '../api/sse'
import type { ApiError, ChatMessage, ChatRole, ChatStatus, NetworkError, PlanTaskInfo, PlanTaskStatus, SessionMessageView, ToolCallInfo } from '../types'

/** 规划任务状态秩：只升级不降级（防御帧乱序，迭代5）。pending<running<终态三态同级。 */
const PLAN_STATUS_RANK: Record<PlanTaskStatus, number> = {
  pending: 0,
  running: 1,
  succeeded: 2,
  failed: 2,
  skipped: 2,
}

/** 同 taskId 合并新老视图：秩升级则整体替换；同级合并补充字段（durationMs/error）；降级保留老值。 */
function mergePlanTask(oldT: PlanTaskInfo | undefined, incoming: PlanTaskInfo): PlanTaskInfo {
  if (!oldT) return incoming
  if (PLAN_STATUS_RANK[incoming.status] > PLAN_STATUS_RANK[oldT.status]) return { ...oldT, ...incoming }
  if (PLAN_STATUS_RANK[incoming.status] === PLAN_STATUS_RANK[oldT.status]) return { ...oldT, ...incoming }
  return oldT
}

let idSeq = 0
function uid(prefix: string): string {
  idSeq += 1
  return `${prefix}-${Date.now().toString(36)}-${idSeq}`
}

function toChatMessage(view: SessionMessageView): ChatMessage {
  return {
    id: uid('m'),
    role: view.role as ChatRole,
    content: view.content,
    createdAt: view.createdAt,
    status: 'done',
  }
}

export interface UseChatStreamOptions {
  /** sessionId 确立（新建）或一轮完成后回调：触发侧边栏刷新 */
  onSessionsChanged?: () => void
}

/**
 * 对话流状态机（FR-11/13/14/15）：消息列表、流状态、当前会话、记忆开关、发送/停止。
 * 请求体三态（FR-11.6）：续接 UUID → sessionId=uuid；新建（remember 开、无当前会话）→ ''；
 * 无状态（remember 关、无当前会话）→ 省略 sessionId 键。
 */
export function useChatStream(options: UseChatStreamOptions = {}) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [status, setStatus] = useState<ChatStatus>('idle')
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null)
  const [remember, setRememberState] = useState(true)
  const [lastError, setLastError] = useState<ApiError | NetworkError | null>(null)

  const controllerRef = useRef<AbortController | null>(null)
  const assistantIdRef = useRef<string | null>(null)
  // 续接态的会话 ID 不受记忆开关影响；用 ref 保存发送时刻的会话归属
  const sessionRef = useRef<string | null>(null)
  const rememberRef = useRef(true)
  rememberRef.current = remember
  sessionRef.current = currentSessionId

  const isStreaming = status === 'streaming'

  const patchAssistant = useCallback((id: string, patch: Partial<ChatMessage>) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)))
  }, [])

  const appendChunk = useCallback((id: string, chunk: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === id ? { ...m, content: m.content + chunk } : m)),
    )
  }, [])

  /**
   * 工具帧按 callId upsert 到当前流式助手消息（插入迭代 G，AC-66/69）：
   * 同 callId（started→终态）合并状态，新 callId 按到达顺序追加；
   * 只挂当前助手消息，历史/新会话天然不带 toolCalls。
   */
  const upsertToolCall = useCallback((id: string, info: ToolCallInfo) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== id) return m
        const existing = m.toolCalls ?? []
        const idx = existing.findIndex((t) => t.callId === info.callId)
        const toolCalls =
          idx >= 0
            ? existing.map((t, i) => (i === idx ? { ...t, ...info } : t))
            : [...existing, info]
        return { ...m, toolCalls }
      }),
    )
  }, [])

  /**
   * plan 帧全量台账按 taskId upsert 到当前流式助手消息（迭代5，AC-37/AC-40）：
   * 新 taskId 按台账顺序追加，已存在按状态秩合并（升级不降级）；
   * 只挂当前助手消息，历史/新会话天然不带 planTasks。
   */
  const upsertPlanTasks = useCallback((id: string, tasks: PlanTaskInfo[]) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== id) return m
        const existing = m.planTasks ?? []
        const byId = new Map(existing.map((t) => [t.taskId, t]))
        const order: string[] = existing.map((t) => t.taskId)
        for (const incoming of tasks) {
          if (!byId.has(incoming.taskId)) {
            byId.set(incoming.taskId, incoming)
            order.push(incoming.taskId)
          } else {
            byId.set(incoming.taskId, mergePlanTask(byId.get(incoming.taskId), incoming))
          }
        }
        return { ...m, planTasks: order.map((tid) => byId.get(tid)!) }
      }),
    )
  }, [])

  /** task 帧按 taskId 推进单个任务状态（started→running，终态覆盖 durationMs/error）。 */
  const upsertPlanTask = useCallback((id: string, info: PlanTaskInfo) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== id) return m
        const existing = m.planTasks ?? []
        const idx = existing.findIndex((t) => t.taskId === info.taskId)
        const planTasks =
          idx >= 0
            ? existing.map((t, i) => (i === idx ? mergePlanTask(t, info) : t))
            : [...existing, info]
        return { ...m, planTasks }
      }),
    )
  }, [])

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim()
      if (!trimmed) return // 空白拦截
      if (controllerRef.current) return // 流式中双保险拦截

      setLastError(null)
      const sid = sessionRef.current
      const body: { message: string; sessionId?: string } = { message: trimmed }
      if (sid) {
        body.sessionId = sid // 续接
      } else if (rememberRef.current) {
        body.sessionId = '' // 新建
      }
      // 无状态：省略 sessionId 键

      const userMsg: ChatMessage = { id: uid('u'), role: 'user', content: trimmed, status: 'done' }
      const assistantId = uid('a')
      assistantIdRef.current = assistantId
      const assistantMsg: ChatMessage = { id: assistantId, role: 'assistant', content: '', status: 'streaming' }
      setMessages((prev) => [...prev, userMsg, assistantMsg])
      setStatus('streaming')

      const controller = new AbortController()
      controllerRef.current = controller

      await streamChat(body, controller, {
        onSession: (newSid) => {
          setCurrentSessionId(newSid)
          sessionRef.current = newSid
          options.onSessionsChanged?.()
        },
        onChunk: (chunk) => appendChunk(assistantId, chunk),
        onTool: (info) => upsertToolCall(assistantId, info),
        onPlan: (_round, tasks) => upsertPlanTasks(assistantId, tasks),
        onTask: (info) => upsertPlanTask(assistantId, info),
        onDone: () => {
          patchAssistant(assistantId, { status: 'done' })
          controllerRef.current = null
          setStatus('idle')
          options.onSessionsChanged?.()
        },
        onError: (error) => {
          patchAssistant(assistantId, { status: 'error', error })
          setLastError(error)
          controllerRef.current = null
          setStatus('error')
        },
        onAbort: () => {
          // 用户主动停止：片段保留、标记「已停止」，不算错误
          patchAssistant(assistantId, { status: 'stopped' })
          controllerRef.current = null
          setStatus('idle')
        },
      })
    },
    [appendChunk, patchAssistant, upsertToolCall, upsertPlanTasks, upsertPlanTask, options],
  )

  const stop = useCallback(() => {
    controllerRef.current?.abort() // 无 reason → 用户停止
  }, [])

  const setRemember = useCallback((v: boolean) => {
    setRememberState(v)
  }, [])

  /** 切换到历史会话：加载升序历史消息并设置续接 ID。 */
  const showHistory = useCallback((sessionId: string, history: SessionMessageView[]) => {
    setLastError(null)
    setCurrentSessionId(sessionId)
    sessionRef.current = sessionId
    setMessages(history.map(toChatMessage))
    setStatus('idle')
    controllerRef.current = null
  }, [])

  /** 「新会话」：清空消息区与会话归属（随后发送按 remember 决定新建/无状态）。 */
  const startNew = useCallback(() => {
    setLastError(null)
    setCurrentSessionId(null)
    sessionRef.current = null
    setMessages([])
    setStatus('idle')
    controllerRef.current = null
  }, [])

  const dismissError = useCallback(() => setLastError(null), [])

  return {
    messages,
    status,
    isStreaming,
    currentSessionId,
    remember,
    setRemember,
    lastError,
    dismissError,
    send,
    stop,
    showHistory,
    startNew,
  }
}

export type UseChatStreamReturn = ReturnType<typeof useChatStream>
