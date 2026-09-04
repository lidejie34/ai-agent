import type { ApiError } from '../types'
import { vi } from 'vitest'

// SSE 测试 mock（FR-19.1）：用 ReadableStream 按脚本推送编码后的 SSE 帧，mock fetch。
// 帧编码与后端 SseEmitter 线格式一致（event:/data: + 空行分隔）。

export const encodeSse = {
  session: (sid: string): string =>
    `event:session\ndata:${JSON.stringify({ sessionId: sid })}\n\n`,
  chunk: (content: string): string =>
    `event:message\ndata:${JSON.stringify({ content })}\n\n`,
  done: (): string => `event:done\ndata:[DONE]\n\n`,
  error: (e: ApiError): string =>
    `event:error\ndata:${JSON.stringify(e)}\n\n`,
  comment: (text = 'keepalive'): string => `:${text}\n\n`,
  tool: (info: {
    callId: string
    tool: string
    arguments?: string
    status: 'started' | 'succeeded' | 'failed'
    durationMs?: number
    error?: string
  }): string => {
    const payload = { arguments: '', ...info }
    return `event:tool\ndata:${JSON.stringify(payload)}\n\n`
  },
}

/**
 * 构造一个 SSE Response：把脚本中的字符串（帧）依次 enqueue 到 ReadableStream。
 * { delayMs } 项配合 fake timers 使用：先 enqueue 前面的帧，到点后再 enqueue 后续帧。
 */
export function sseResponse(
  script: Array<string | { delayMs: number; chunks?: string[] }>,
  init?: ResponseInit,
): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const item of script) {
        if (typeof item === 'string') {
          controller.enqueue(encoder.encode(item))
        } else if (item.chunks) {
          // delay 项：先不推，供定时器回调推（测试里手动控制）；此处简化为同步推 chunks
          for (const c of item.chunks) controller.enqueue(encoder.encode(c))
        }
      }
      controller.close()
    },
  })
  return new Response(body, {
    headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
    ...init,
  })
}

/**
 * 可调度的 SSE 流：测试持有 push/close/error，自行控制入队时机（看门狗假时钟用）。
 */
export function controllableSse(): {
  response: Response
  push: (frame: string) => void
  close: () => void
  error: (e: unknown) => void
} {
  const encoder = new TextEncoder()
  let controllerRef: ReadableStreamDefaultController<Uint8Array> | null = null
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controllerRef = c
    },
  })
  return {
    response: new Response(body, {
      headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
    }),
    push: (frame: string) => controllerRef?.enqueue(encoder.encode(frame)),
    close: () => controllerRef?.close(),
    error: (e: unknown) => controllerRef?.error(e),
  }
}

/** 让下一次 fetch 返回给定 Response。 */
export function mockFetchOnce(resp: Response | (() => Response)): ReturnType<typeof vi.fn> {
  const fn = vi.fn(async () => (typeof resp === 'function' ? resp() : resp))
  vi.stubGlobal('fetch', fn)
  return fn
}

/**
 * mock fetch 返回可控 SSE 流，并把请求的 AbortSignal 接到流：
 * 真实浏览器中 abort fetch 会令 body 流报错、reader.read() reject；
 * 手动 ReadableStream 不会自动联动 signal，故在此显式接线（停止/看门狗测试依赖）。
 */
export function mockControllableFetch(sse: {
  response: Response
  error: (e: unknown) => void
}): ReturnType<typeof vi.fn> {
  const fn = vi.fn(async (_url: string, init?: RequestInit) => {
    init?.signal?.addEventListener('abort', () => {
      try {
        sse.error(new DOMException('Aborted', 'AbortError'))
      } catch {
        // 流已关闭则忽略
      }
    })
    return sse.response
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

/** 非 200 的 JSON 错误响应（HTTP 错误体，非 SSE 流）。 */
export function jsonErrorResponse(error: ApiError, status: number): Response {
  return new Response(JSON.stringify(error), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 读取最近一次 fetch 调用的请求体（已 JSON.parse）。 */
export function lastFetchBody(fn: ReturnType<typeof vi.fn>): unknown {
  const call = fn.mock.calls[fn.mock.calls.length - 1]
  const init = call?.[1] as RequestInit | undefined
  return init?.body ? JSON.parse(init.body as string) : undefined
}
