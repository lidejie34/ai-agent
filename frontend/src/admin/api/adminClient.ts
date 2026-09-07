import { apiFetch, networkError } from '../../api/http'
import type { ApiError } from '../../types'
import { getAdminToken, notifyUnauthorized } from '../auth'

// 管理端统一 fetch（迭代 H）：
// - 仅用于 /api/admin/**；自动注入 X-Admin-Token（内存态优先，其次 localStorage）；
// - 复用 apiFetch 的 JSON/204/错误归一（parseErrorResponse）；
// - 401 ADMIN_UNAUTHORIZED → notifyUnauthorized()（顶层登出）后继续抛；
// - fetch 层 reject（断网/CORS）归一为 NetworkError(NETWORK_ERROR)。
// token 绝不入 URL、绝不 console.*（AC-13）。

const ADMIN_TOKEN_HEADER = 'X-Admin-Token'

function isApiError(e: unknown): e is ApiError {
  return !!e && typeof e === 'object' && typeof (e as ApiError).code === 'string'
}

export async function adminFetch<T>(input: string, init?: RequestInit): Promise<T> {
  const token = getAdminToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  }
  if (token) headers[ADMIN_TOKEN_HEADER] = token
  try {
    return await apiFetch<T>(input, { ...init, headers })
  } catch (e) {
    if (isApiError(e)) {
      if (e.code === 'ADMIN_UNAUTHORIZED') notifyUnauthorized()
      throw e
    }
    throw networkError() // TypeError 等网络层错误
  }
}
