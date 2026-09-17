import type { RefObject } from 'react'
import { useAutoScroll } from '../hooks/useAutoScroll'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../types'

export interface MessageListProps {
  messages: ChatMessage[]
  /** 迭代13：删除单轮（仅流式空闲时由父级传入，缺席则操作按钮不渲染） */
  onDeleteTurn?: (message: ChatMessage) => void
  /** 迭代13：从该条起截断重问 */
  onTruncate?: (message: ChatMessage) => void
}

export default function MessageList({ messages, onDeleteTurn, onTruncate }: MessageListProps) {
  // 流式 chunk / 状态变化都触发贴底滚动评估
  const contentSig = messages
    .map((m) => `${m.role}:${m.status}:${m.content}:${m.divider ? 1 : 0}`)
    .join('|')
  const { containerRef, atBottom, scrollToBottom } = useAutoScroll(contentSig)

  return (
    <div className="message-list-wrap">
      <div
        className="message-list-scroll"
        data-testid="message-list-scroll"
        ref={containerRef as RefObject<HTMLDivElement>}
      >
        {messages.map((m) =>
          m.divider ? (
            // 迭代13：「清空上下文」标记 → 分隔线（FR-5 记忆边界可见）
            <div className="context-divider" data-testid="context-divider" key={m.id}>
              <span className="context-divider-text">上下文已清空 · 以上历史不再携带</span>
            </div>
          ) : (
            <MessageBubble
              key={m.id}
              message={m}
              onDeleteTurn={onDeleteTurn}
              onTruncate={onTruncate}
            />
          ),
        )}
      </div>
      {!atBottom && (
        <button
          type="button"
          className="back-to-bottom"
          data-testid="back-to-bottom"
          onClick={scrollToBottom}
        >
          ↓ 回到底部
        </button>
      )}
    </div>
  )
}
