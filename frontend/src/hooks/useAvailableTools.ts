import { useEffect, useState } from 'react'
import { getAvailableTools, type ToolsAvailableOptions } from '../api/tools'

/**
 * 聊天页工具选择器选项（迭代12）：挂载时拉一次 /api/tools/available。
 * 请求失败或两侧皆空（工具开关关/无 MCP server）→ available=false，
 * 调用方静默隐藏选择器，不影响对话主流程（同 useKbDimensions 纪律）。
 * MCP 只暴露 READY server 供选择（UNAVAILABLE 挂载时本就会被摘除）。
 */
export function useAvailableTools(): { dbTools: string[]; mcpServers: string[]; available: boolean } {
  const [opts, setOpts] = useState<ToolsAvailableOptions>({ dbTools: [], mcpServers: [] })

  useEffect(() => {
    let alive = true
    getAvailableTools()
      .then((res) => {
        if (alive) {
          setOpts({ dbTools: res.dbTools ?? [], mcpServers: res.mcpServers ?? [] })
        }
      })
      .catch(() => {
        // 静默降级：选项加载失败不打扰对话
      })
    return () => {
      alive = false
    }
  }, [])

  const dbTools = opts.dbTools.map((t) => t.name)
  const mcpServers = opts.mcpServers.filter((s) => s.status === 'READY').map((s) => s.name)
  return { dbTools, mcpServers, available: dbTools.length > 0 || mcpServers.length > 0 }
}
