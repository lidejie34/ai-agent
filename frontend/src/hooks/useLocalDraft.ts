import { useCallback, useEffect, useState } from 'react'
import { readDraft, writeDraft } from '../utils/storage'

/**
 * 输入草稿持久化（FR-12.x）：按会话作用域隔离，
 * 键为 `chat.draft:<sessionId|'global'>`；发送后置空移除。
 * 切换作用域（切换/新建会话）时自动加载该作用域的草稿。
 */
export function useLocalDraft(scope: string) {
  const [draft, setDraftState] = useState('')

  // 作用域变化（挂载/切换会话）时从 localStorage 恢复
  useEffect(() => {
    setDraftState(readDraft(scope))
  }, [scope])

  const setDraft = useCallback(
    (value: string) => {
      setDraftState(value)
      writeDraft(scope, value)
    },
    [scope],
  )

  return { draft, setDraft }
}
