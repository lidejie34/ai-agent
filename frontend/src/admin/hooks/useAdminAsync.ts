import { useCallback, useEffect, useRef, useState } from 'react'
import { networkError } from '../../api/http'
import type { ApiError, NetworkError } from '../../types'

// 管理端通用数据加载（迭代 H）：loading/error/refresh + seq 序号丢弃过期响应（AC-47）。
// 卸载或参数变更后旧响应不再 setState；401 已在 client 层广播登出。

function isApiError(e: unknown): e is ApiError {
  return !!e && typeof e === 'object' && typeof (e as ApiError).code === 'string'
}

export function useAdminAsync<T>(fetcher: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | NetworkError | null>(null)
  const seqRef = useRef(0)

  const refresh = useCallback(async () => {
    const seq = ++seqRef.current
    setLoading(true)
    setError(null)
    try {
      const d = await fetcher()
      if (seqRef.current === seq) setData(d)
    } catch (e) {
      if (seqRef.current === seq) setError(isApiError(e) ? e : networkError())
    } finally {
      if (seqRef.current === seq) setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    void refresh()
  }, [refresh])

  return { data, loading, error, refresh, setData }
}
