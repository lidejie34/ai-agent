import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import MessageBubble from './MessageBubble'
import type { ApiError, ChatMessage, ToolCallInfo } from '../types'

function msg(overrides: Partial<ChatMessage>): ChatMessage {
  return { id: 'm1', role: 'assistant', content: '你好', status: 'done', ...overrides }
}

const started = (callId: string, tool: string): ToolCallInfo => ({
  callId,
  tool,
  arguments: '{"minutes":30}',
  status: 'started',
})
const succeeded = (callId: string, tool: string, durationMs = 420): ToolCallInfo => ({
  callId,
  tool,
  arguments: '{"minutes":30}',
  status: 'succeeded',
  durationMs,
})
const failed = (callId: string, tool: string): ToolCallInfo => ({
  callId,
  tool,
  arguments: '{}',
  status: 'failed',
  durationMs: 3000,
  error: '工具执行超时（3000ms）',
})

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

  it('工具调用折叠块：started 显示「🔧 调用工具 + 执行中」，块在正文 Markdown 上方', () => {
    const { container } = render(
      <MessageBubble message={msg({ content: '分析结果', toolCalls: [started('c1', 'analyze_log')] })} />,
    )
    const blocks = screen.getAllByTestId('tool-call-block')
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toHaveTextContent('🔧 调用工具 analyze_log')
    const status = screen.getByTestId('tool-call-status')
    expect(status).toHaveAttribute('data-status', 'started')
    expect(status).toHaveTextContent('执行中')
    // 折叠块在正文 Markdown 之前
    const bubble = screen.getByTestId('message-bubble')
    const blocksContainer = bubble.querySelector('.tool-call-blocks')
    const markdown = bubble.querySelector('.markdown-body')
    expect(blocksContainer).not.toBeNull()
    expect(markdown).not.toBeNull()
    expect(
      blocksContainer!.compareDocumentPosition(markdown!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(container.textContent).toContain('"minutes"') // 入参摘要
  })

  it('工具成功：绿色状态显示耗时；无 error 区', () => {
    render(<MessageBubble message={msg({ toolCalls: [succeeded('c1', 'analyze_log', 420)] })} />)
    const status = screen.getByTestId('tool-call-status')
    expect(status).toHaveAttribute('data-status', 'succeeded')
    expect(status).toHaveTextContent('成功')
    expect(status).toHaveTextContent('420ms')
    expect(screen.queryByTestId('tool-call-error')).toBeNull()
  })

  it('工具失败：红色状态 + 展开区错误摘要', () => {
    render(<MessageBubble message={msg({ toolCalls: [failed('c2', 'log_error_count')] })} />)
    const status = screen.getByTestId('tool-call-status')
    expect(status).toHaveAttribute('data-status', 'failed')
    expect(status).toHaveTextContent('失败')
    expect(screen.getByTestId('tool-call-error')).toHaveTextContent('工具执行超时')
  })

  it('多工具调用：按顺序渲染多块', () => {
    render(
      <MessageBubble
        message={msg({ toolCalls: [started('c1', 'analyze_log'), succeeded('c2', 'log_error_count', 15)] })}
      />,
    )
    const blocks = screen.getAllByTestId('tool-call-block')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toHaveAttribute('data-tool', 'analyze_log')
    expect(blocks[1]).toHaveAttribute('data-tool', 'log_error_count')
  })

  it('无工具调用的消息不渲染折叠块容器', () => {
    render(<MessageBubble message={msg({ content: '普通回复' })} />)
    expect(screen.queryByTestId('tool-call-blocks')).toBeNull()
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
