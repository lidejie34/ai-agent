import { useEffect, useState } from 'react'
import { getKbDimensions, type KbDimensionOptions } from '../api/kb'

/**
 * 聊天页维度选项（迭代10 追加）：挂载时拉一次 /api/kb/dimensions。
 * 请求失败或两端皆空（RAG 未启用/未维护任何维度）→ available=false，
 * 调用方静默隐藏选择器，不影响对话主流程。
 */
export function useKbDimensions(): KbDimensionOptions & { available: boolean } {
  const [dims, setDims] = useState<KbDimensionOptions>({ projects: [], tags: [] })

  useEffect(() => {
    let alive = true
    getKbDimensions()
      .then((res) => {
        if (alive) setDims({ projects: res.projects ?? [], tags: res.tags ?? [] })
      })
      .catch(() => {
        // 静默降级：维度加载失败不打扰对话
      })
    return () => {
      alive = false
    }
  }, [])

  return { ...dims, available: dims.projects.length > 0 || dims.tags.length > 0 }
}
