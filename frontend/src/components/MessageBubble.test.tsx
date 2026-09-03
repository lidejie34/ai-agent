import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import MessageBubble from './MessageBubble'
import type { ApiError, ChatMessage } from '../types'

function msg(overrides: Partial<ChatMessage>): ChatMessage {
  return { id: 'm1', role: 'assistant', content: '你好', status: 'done', ...overrides }
}

describe('MessageBubble', () => {
  it('用户消息：右对齐、纯文本保留换行', () => {
    render(<MessageBubble message={msg({ id: 'u1', role: 'user', content: '第一行\n第二行' })} />)
    const bubble = screen.getByTestId('message-bubble')
    expect(bubble).toHaveAttribute('data-role', 'user')
    expect(bubble).toHaveTextContent('第一行')
    expect(bubble).toHaveTextContent('第二行')
    // 用户消息不走 Markdown
    expect(bubble.querySelector('.markdown-body')).toBeNull()
  })

  it('助手消息：左对齐、Markdown 渲染', () => {
    render(<MessageBubble message={msg({ content: '这是 **加粗** 内容' })} />)
    const bubble = screen.getByTestId('message-bubble')
    expect(bubble).toHaveAttribute('data-role', 'assistant')
    expect(bubble.querySelector('strong')).toHaveTextContent('加粗')
  })

  it('流式中：显示生成中指示', () => {
    render(<MessageBubble message={msg({ content: '半截内容', status: 'streaming' })} />)
    expect(screen.getByTestId('streaming-indicator')).toBeInTheDocument()
  })

  it('用户主动停止：片段保留并标注「已停止」', () => {
    render(<MessageBubble message={msg({ content: '已生成的片段', status: 'stopped' })} />)
    expect(screen.getByTestId('message-bubble')).toHaveTextContent('已生成的片段')
    expect(screen.getByText('已停止')).toBeInTheDocument()
    expect(screen.queryByTestId('streaming-indicator')).toBeNull()
  })

  it('出错：展示按错误码映射的可读文案', () => {
    const error: ApiError = {
      code: 'MEMORY_UNAVAILABLE',
      message: '原始错误',
      timestamp: '2026-09-03 15:00:00',
    }
    render(<MessageBubble message={msg({ status: 'error', error, content: '' })} />)
    expect(screen.getByTestId('message-error')).toHaveTextContent(
      '会话记忆暂不可用，可关闭「记住本次对话」以无状态继续',
    )
  })
})
