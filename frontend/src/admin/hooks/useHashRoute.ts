import { useCallback, useEffect, useState } from 'react'

// 管理控制台 hash 路由（迭代 H，T0）：不引 react-router，约 40 行自实现。
// 路由表：#/admin、#/admin/tools → 工具注册表（默认）；#/admin/mcp → MCP；
// #/admin/logs → 审计；#/admin/xxx 未知 → 回落 tools（AC-2/4）。

export type AdminPageKey = 'tools' | 'mcp' | 'logs' | 'kb'

const PAGE_KEYS: AdminPageKey[] = ['tools', 'mcp', 'logs', 'kb']
export const DEFAULT_ADMIN_PAGE: AdminPageKey = 'tools'

export interface HashRoute {
  isAdmin: boolean
  page: AdminPageKey
}

function parseHash(): HashRoute {
  const hash = window.location.hash.replace(/^#/, '') // 形如 /admin/mcp
  const m = hash.match(/^\/admin(?:\/([a-z]+))?\/?$/)
  if (!m) return { isAdmin: false, page: DEFAULT_ADMIN_PAGE }
  const seg = m[1]
  const page = (PAGE_KEYS as string[]).includes(seg ?? '') ? (seg as AdminPageKey) : DEFAULT_ADMIN_PAGE
  return { isAdmin: true, page }
}

export function useHashRoute(): HashRoute & {
  navigate: (to: string) => void
  backToChat: () => void
} {
  const [route, setRoute] = useState<HashRoute>(parseHash)

  useEffect(() => {
    const onChange = () => setRoute(parseHash())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  // Tabs 切换：写 hash（AC-4，hash 联动、刷新停留）
  const navigate = useCallback((to: string) => {
    window.location.hash = to
  }, [])

  // 返回对话：replaceState 清掉残留的 '#'，手动派发 hashchange（AC-3）
  const backToChat = useCallback(() => {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
    window.dispatchEvent(new HashChangeEvent('hashchange'))
  }, [])

  return { ...route, navigate, backToChat }
}
