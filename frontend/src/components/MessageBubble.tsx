import { Button, Popconfirm, Tag } from 'antd'
import MarkdownView from './MarkdownView'
import PlanTaskBlocks from './PlanTaskBlocks'
import ToolCallBlocks from './ToolCallBlocks'
import { errorCodeToText } from '../utils/errors'
import type { ChatMessage } from '../types'

export interface MessageBubbleProps {
  message: ChatMessage
  /** 迭代13：删除单轮（父级仅在流式空闲时传入；缺席则不渲染操作按钮） */
  onDeleteTurn?: (message: ChatMessage) => void
  /** 迭代13：从该条起截断重问 */
  onTruncate?: (message: ChatMessage) => void
}

export default function MessageBubble({ message, onDeleteTurn, onTruncate }: MessageBubbleProps) {
  const isUser = message.role === 'user'
  // 消息级操作：仅 user 气泡 + 已持库 id（历史加载/轮次对齐后）+ 父级放行（非流式）
  const showActions = isUser && message.backendId != null && (onDeleteTurn || onTruncate)

  return (
    <div className={`message-row ${isUser ? 'message-row-user' : 'message-row-assistant'}`}>
      {showActions && (
        <span className="msg-actions" data-testid={`msg-actions-${message.backendId}`}>
          {onTruncate && (
            <Popconfirm
              title="从这条起截断重问？"
              description="将删除该条及之后的全部消息（上下文清空点保留），内容回填输入框"
              okText="截断"
              cancelText="取消"
              okButtonProps={{ danger: true, 'data-testid': `msg-truncate-confirm-${message.backendId}` } as never}
              onConfirm={() => onTruncate(message)}
            >
              <Button
                type="text"
                size="small"
                data-testid={`msg-truncate-${message.backendId}`}
              >
                截断重问
              </Button>
            </Popconfirm>
          )}
          {onDeleteTurn && (
            <Popconfirm
              title="删除本轮对话？"
              description="将删除这条消息及其 AI 回复，不可恢复"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true, 'data-testid': `msg-delete-turn-confirm-${message.backendId}` } as never}
              onConfirm={() => onDeleteTurn(message)}
            >
              <Button
                type="text"
                size="small"
                danger
                data-testid={`msg-delete-turn-${message.backendId}`}
              >
                删除本轮
              </Button>
            </Popconfirm>
          )}
        </span>
      )}
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
            {/* SDD 规划面板：仅当前流式助手消息持有，位于工具折叠块上方（迭代5，AC-37/39） */}
            {message.planTasks && message.planTasks.length > 0 && (
              <PlanTaskBlocks planTasks={message.planTasks} />
            )}
            {/* 工具调用折叠块：仅助手消息、正文 Markdown 上方（历史消息无 toolCalls，AC-68） */}
            {message.toolCalls && message.toolCalls.length > 0 && (
              <ToolCallBlocks toolCalls={message.toolCalls} />
            )}
            {message.content ? (
              <MarkdownView content={message.content} />
            ) : message.status === 'streaming' ? null : (
              <div className="bubble-text placeholder">（空回复）</div>
            )}
            {message.status === 'streaming' && (
              <span className="streaming-indicator" data-testid="streaming-indicator" aria-label="正在生成">
                {/* 界面美化：三点波浪（错峰 delay 见 index.css） */}
                <span className="streaming-dot" />
                <span className="streaming-dot" />
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
