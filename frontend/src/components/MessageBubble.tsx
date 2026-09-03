import { Tag } from 'antd'
import MarkdownView from './MarkdownView'
import { errorCodeToText } from '../utils/errors'
import type { ChatMessage } from '../types'

export default function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === 'user'

  return (
    <div className={`message-row ${isUser ? 'message-row-user' : 'message-row-assistant'}`}>
      <div
        className={`message-bubble ${isUser ? 'bubble-user' : 'bubble-assistant'}`}
        data-testid="message-bubble"
        data-role={message.role}
      >
        {isUser ? (
          // 用户输入按纯文本展示（white-space: pre-wrap 保留换行），不做 Markdown 渲染
          <div className="bubble-text">{message.content}</div>
        ) : (
          <>
            {message.content ? (
              <MarkdownView content={message.content} />
            ) : message.status === 'streaming' ? null : (
              <div className="bubble-text placeholder">（空回复）</div>
            )}
            {message.status === 'streaming' && (
              <span className="streaming-indicator" data-testid="streaming-indicator" aria-label="正在生成">
                <span className="streaming-dot" />
                生成中…
              </span>
            )}
            {message.status === 'stopped' && (
              <div className="message-meta">
                <Tag color="default">已停止</Tag>
              </div>
            )}
            {message.status === 'error' && (
              <div className="message-error" data-testid="message-error">
                {errorCodeToText(message.error?.code, message.error?.message)}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
