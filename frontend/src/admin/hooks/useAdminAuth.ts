import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import {
  clearAdminToken,
  getAdminToken,
  onUnauthorized,
  persistAdminToken,
  stageToken,
} from '../auth'
import { verifyAdminToken } from '../api/adminApi'

// 管理端登录态（迭代 H）：
// - login：stageToken（仅内存）→ GET /api/admin/tools 验证 → 成功才 persist 落盘（AC-7/8）；
// - 401 广播：仅已登录态响应一次（ref 去重，AC-11/47），清 token + 回登录页 + 提示；
// - logout：Popconfirm 确认后调用，落点为控制台内登录页。

export function useAdminAuth() {
  const [authed, setAuthed] = useState(() => getAdminToken() !== null) // 刷新后从 localStorage 恢复
  const authedRef = useRef(authed)

  useEffect(() => {
    const unsub = onUnauthorized(() => {
      if (!authedRef.current) return // 未登录/已登出后到达的 401 不重复提示（AC-47）
      authedRef.current = false
      clearAdminToken()
      setAuthed(false)
      message.warning('登录已失效，请重新登录')
    })
    return unsub
  }, [])

  const login = useCallback(async (token: string) => {
    const t = token.trim()
    stageToken(t) // 验证期只存内存，失败不落盘（AC-8）
    try {
      await verifyAdminToken()
      persistAdminToken(t)
      authedRef.current = true
      setAuthed(true)
    } catch (e) {
      clearAdminToken() // 清内存（从未写盘）
      throw e
    }
  }, [])

  const logout = useCallback(() => {
    clearAdminToken()
    authedRef.current = false
    setAuthed(false)
  }, [])

  return { authed, login, logout }
}
