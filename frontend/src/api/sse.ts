import type { ApiError, NetworkError, ToolCallInfo } from '../types'
import { drainFrames } from '../utils/sseFrames'
import { isWatchdogTimeout, networkError, parseErrorResponse, watchdogTimeoutError } from './http'

// SSE 流客户端（FR-11）：fetch + reader + TextDecoder(stream) 手写分帧；
// 30s 看门狗（任意数据到达即重置，后端心跳 15s 的 2x 余量）；AbortController 双 reason：
// 用户停止（无 reason）→ onAbort 不报错；看门狗超时（WATCHDOG_TIMEOUT reason）→ onError。

export const WATCHDOG_MS = 30_000

export interface StreamHandlers {
  onSession?: (sessionId: string) => void
  onChunk: (content: string) => void
  /** event:tool 工具生命周期帧（插入迭代 G）：按 callId upsert 折叠块 */
  onTool?: (info: ToolCallInfo) => void
  onDone: () => void
  onError: (error: ApiError | NetworkError) => void
  onAbort: () => void
}

/**
 * 发起流式对话。controller 由调用方持有（停止按钮 / 看门狗共用）：
 * 看门狗超时调用 {@code controller.abort(reason)}，用户停止由调用方 abort（无 reason）。
 */
export async function streamChat(
  body: { message: string; sessionId?: string },
  controller: AbortController,
  handlers: StreamHandlers,
): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const arm = () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      const reason = new Error('WATCHDOG_TIMEOUT')
      reason.name = 'WATCHDOG_TIMEOUT'
      controller.abort(reason)
    }, WATCHDOG_MS)
  }
  const clearTimer = () => {
    if (timer) clearTimeout(timer)
    timer = undefined
  }

  const abortedByUser = () => controller.signal.aborted && !isWatchdogTimeout(controller.signal.reason)

  arm()
  let res: Response
  try {
    res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
  } catch {
    clearTimer()
    if (isWatchdogTimeout(controller.signal.reason)) handlers.onError(watchdogTimeoutError())
    else if (abortedByUser()) handlers.onAbort()
    else handlers.onError(networkError())
    return
  }

  if (!res.ok || !res.body) {
    clearTimer()
    if (abortedByUser()) {
      handlers.onAbort()
      return
    }
    handlers.onError(await parseErrorResponse(res))
    return
  }

  const reader = res.body.getReader()
  // stream:true 在每次 decode 调用时传入（跨 chunk 的多字节字符不截断）
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const finishAbort = () => {
    clearTimer()
    if (isWatchdogTimeout(controller.signal.reason)) handlers.onError(watchdogTimeoutError())
    else handlers.onAbort()
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      arm() // 任意数据到达（含 :keepalive 注释帧字节）即重置看门狗
      buffer += decoder.decode(value, { stream: true })
      const { frames, rest } = drainFrames(buffer)
      buffer = rest
      for (const f of frames) {
        switch (f.kind) {
          case 'comment':
            break // 仅重置看门狗，不产生界面消息
          case 'session':
            handlers.onSession?.(f.sessionId)
            break
          case 'chunk':
            handlers.onChunk(f.content)
            break
          case 'tool':
            handlers.onTool?.(f.info)
            break
          case 'done':
            clearTimer()
            handlers.onDone()
            reader.cancel().catch(() => {})
            return
          case 'error':
            clearTimer()
            handlers.onError(f.error)
            reader.cancel().catch(() => {})
            return
        }
      }
    }
    // 流正常关闭但未见 done 帧（防御）：按完成处理
    clearTimer()
    handlers.onDone()
  } catch {
    // reader.read() 被 abort 拒绝（AbortError）或网络中断
    reader.cancel().catch(() => {})
    if (controller.signal.aborted) finishAbort()
    else {
      clearTimer()
      handlers.onError(networkError())
    }
  }
}
