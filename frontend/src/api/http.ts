import type { ApiError, NetworkError } from '../types'

// JSON fetch 封装与错误归一（FR-11.4/11.5）：非 2xx 解析 ApiError JSON；
// 解析失败按状态码合成；网络层 reject 由调用方归一为 NETWORK_ERROR。

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text()
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return null
  }
}

/** 解析非 2xx 响应体为 ApiError；体不是合法 ApiError 时按状态码合成。 */
export async function parseErrorResponse(res: Response): Promise<ApiError> {
  const obj = (await readJson(res)) as Partial<ApiError> | null
  if (obj && typeof obj.code === 'string') {
    return {
      code: obj.code,
      message: typeof obj.message === 'string' ? obj.message : '',
      timestamp: typeof obj.timestamp === 'string' ? obj.timestamp : '',
    }
  }
  const code = res.status >= 500 ? 'MODEL_CALL_FAILED' : 'BAD_REQUEST'
  return { code, message: '', timestamp: '' }
}

export function networkError(message = '网络连接中断，请检查网络后重试'): NetworkError {
  return { code: 'NETWORK_ERROR', message }
}

export function watchdogTimeoutError(): NetworkError {
  return { code: 'WATCHDOG_TIMEOUT', message: '连接超时（长时间无响应），已停止生成，可重试' }
}

/** 判定 abort reason 是否为看门狗超时（区别于用户主动停止）。 */
export function isWatchdogTimeout(reason: unknown): boolean {
  return !!reason && typeof reason === 'object' && (reason as { name?: string }).name === 'WATCHDOG_TIMEOUT'
}

/** 统一 JSON GET/DELETE/PATCH 封装；非 2xx 抛 ApiError。 */
export async function apiFetch<T>(input: string, init?: RequestInit): Promise<T> {
  const res = await fetch(input, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    throw await parseErrorResponse(res)
  }
  if (res.status === 204) return undefined as T
  return (await readJson(res)) as T
}
