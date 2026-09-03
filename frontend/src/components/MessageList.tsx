import type { RefObject } from 'react'
import { useAutoScroll } from '../hooks/useAutoScroll'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../types'

export default function MessageList({ messages }: { messages: ChatMessage[] }) {
  // 流式 chunk / 状态变化都触发贴底滚动评估
  const contentSig = messages.map((m) => `${m.role}:${m.status}:${m.content}`).join('|')
  const { containerRef, atBottom, scrollToBottom } = useAutoScroll(contentSig)

  return (
    <div className="message-list-wrap">
      <div
        className="message-list-scroll"
        data-testid="message-list-scroll"
        ref={containerRef as RefObject<HTMLDivElement>}
      >
        {messages.map((m) => (
          <MessageBubble key={m.id} message={m} />
        ))}
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
