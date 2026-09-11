import { apiFetch, networkError, parseErrorResponse } from '../../api/http'
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

/**
 * multipart 上传（迭代6 知识库）：绝不手设 Content-Type——浏览器必须自行生成
 * `multipart/form-data; boundary=...`，手设 application/json 或缺 boundary 都会导致后端
 * 解析失败。其余（token 注入、401 广播、错误归一）与 adminFetch 完全一致。
 */
export async function adminUpload<T>(input: string, form: FormData): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getAdminToken()
  if (token) headers[ADMIN_TOKEN_HEADER] = token
  try {
    const res = await fetch(input, { method: 'POST', headers, body: form })
    if (!res.ok) throw await parseErrorResponse(res)
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  } catch (e) {
    if (isApiError(e)) {
      if (e.code === 'ADMIN_UNAUTHORIZED') notifyUnauthorized()
      throw e
    }
    throw networkError()
  }
}
