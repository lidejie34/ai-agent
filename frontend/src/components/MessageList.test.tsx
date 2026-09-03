import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import MessageList from './MessageList'
import type { ChatMessage } from '../types'

function m(id: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return { id, role: 'assistant' as const, content: `内容${id}`, status: 'done', ...overrides }
}

// jsdom 无布局：钉死滚动几何（同 useAutoScroll 测试）
function stubScrollGeometry() {
  const scrollTops = new WeakMap<object, number>()
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, value: 100 })
  Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, value: 1000 })
  Object.defineProperty(HTMLElement.prototype, 'scrollTop', {
    configurable: true,
    get(this: HTMLElement) {
      return scrollTops.get(this) ?? 0
    },
    set(this: HTMLElement, v: number) {
      scrollTops.set(this, v)
    },
  })
}

describe('MessageList', () => {
  it('按顺序渲染全部消息（用户/助手）', () => {
    stubScrollGeometry()
    const messages: ChatMessage[] = [
      m('1', { role: 'user', content: '你好' }),
      m('2', { role: 'assistant', content: '你好呀' }),
    ]
    render(<MessageList messages={messages} />)
    const bubbles = screen.getAllByTestId('message-bubble')
    expect(bubbles).toHaveLength(2)
    expect(bubbles[0]).toHaveAttribute('data-role', 'user')
    expect(bubbles[1]).toHaveAttribute('data-role', 'assistant')
  })

  it('上滑脱离贴底时出现「回到底部」，点击后消失并回底', () => {
    stubScrollGeometry()
    render(<MessageList messages={[m('1', { content: '长内容' })]} />)
    // 初始贴底：无按钮
    expect(screen.queryByTestId('back-to-bottom')).toBeNull()

    const scroller = screen.getByTestId('message-list-scroll')
    scroller.scrollTop = 200 // 距底 700px
    fireEvent.scroll(scroller)

    const btn = screen.getByTestId('back-to-bottom')
    expect(btn).toBeInTheDocument()
    fireEvent.click(btn)
    expect(scroller.scrollTop).toBe(1000)
    expect(screen.queryByTestId('back-to-bottom')).toBeNull()
  })
})
