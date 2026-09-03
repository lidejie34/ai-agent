import { useCallback, useEffect, useRef, useState } from 'react'

// 贴底阈值（px）：距底小于该值视为「仍在底部」，新内容自动滚动；
// 用户上滑超过该值则脱离贴底，流式更新不抢滚动条（FR-13.x 阅读体验）。
const STICK_THRESHOLD_PX = 80

/**
 * 消息区自动滚动：
 * - 贴底态：content 变化（流式 chunk / 切换会话）时滚到最下；
 * - 用户上滑超过阈值 → 脱离贴底，显示「回到底部」；
 * - 调用 scrollToBottom() 手动回底并恢复贴底。
 */
export function useAutoScroll(content: unknown) {
  const containerRef = useRef<HTMLElement | null>(null)
  const stickRef = useRef(true)
  const [atBottom, setAtBottom] = useState(true)

  const scrollToBottom = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    stickRef.current = true
    setAtBottom(true)
    el.scrollTop = el.scrollHeight
  }, [])

  // content 变化时，贴底态自动滚到底
  useEffect(() => {
    const el = containerRef.current
    if (el && stickRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [content])

  const handleScroll = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    const at = distance <= STICK_THRESHOLD_PX
    stickRef.current = at
    setAtBottom(at)
  }, [])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    el.addEventListener('scroll', handleScroll, { passive: true })
    return () => el.removeEventListener('scroll', handleScroll)
  }, [handleScroll])

  return { containerRef, atBottom, scrollToBottom }
}
