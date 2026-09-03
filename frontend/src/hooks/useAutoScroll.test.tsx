import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { type RefObject } from 'react'
import { useAutoScroll } from './useAutoScroll'

// jsdom 无布局：手动钉死 clientHeight/scrollHeight/scrollTop，
// 用 fireEvent.scroll 模拟用户滚动。
function Harness({ content }: { content: string }) {
  const { containerRef, atBottom, scrollToBottom } = useAutoScroll(content)
  const ref = containerRef as RefObject<HTMLDivElement>
  return (
    <>
      <div ref={ref} data-testid="scroller" style={{ height: 100, overflow: 'auto' }}>
        <div style={{ height: 1000 }} data-testid="inner">
          {content}
        </div>
      </div>
      <span data-testid="at-bottom">{String(atBottom)}</span>
      <button type="button" data-testid="to-bottom" onClick={scrollToBottom}>
        回到底部
      </button>
    </>
  )
}

// jsdom 无布局：在原型上钉死滚动几何（每个测试文件独立 jsdom 环境，无污染）。
// 必须在 render 前定义——贴底 effect 在挂载时就会读 scrollHeight。
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

function mount(content: string) {
  stubScrollGeometry()
  const utils = render(<Harness content={content} />)
  const el = screen.getByTestId('scroller') as HTMLDivElement
  const userScrollTo = (v: number) => {
    el.scrollTop = v
    fireEvent.scroll(el)
  }
  return { ...utils, el, getScrollTop: () => el.scrollTop, userScrollTo }
}

describe('useAutoScroll', () => {
  it('内容变化时贴底（自动滚到最下）', () => {
    const { getScrollTop, rerender } = mount('a')
    expect(getScrollTop()).toBe(1000) // scrollHeight
    expect(screen.getByTestId('at-bottom')).toHaveTextContent('true')

    // 新内容（流式 chunk）到达，仍贴底
    rerender(<Harness content="ab" />)
    expect(getScrollTop()).toBe(1000)
  })

  it('用户上滚（超过阈值）后，新内容不再强制回底；回到底部后恢复贴底', () => {
    const { getScrollTop, userScrollTo, rerender } = mount('a')
    expect(getScrollTop()).toBe(1000)

    // 用户向上滚到 200px：距底 1000-200-100=700 > 80 阈值 → 脱离贴底
    userScrollTo(200)
    expect(screen.getByTestId('at-bottom')).toHaveTextContent('false')

    // 新内容到达不抢滚动条
    rerender(<Harness content="b" />)
    expect(getScrollTop()).toBe(200)

    // 点「回到底部」→ 恢复贴底
    fireEvent.click(screen.getByTestId('to-bottom'))
    expect(getScrollTop()).toBe(1000)
    expect(screen.getByTestId('at-bottom')).toHaveTextContent('true')

    // 后续新内容继续贴底
    rerender(<Harness content="c" />)
    expect(getScrollTop()).toBe(1000)
  })

  it('距底 80px 以内仍视为贴底（阈值容差）', () => {
    const { userScrollTo } = mount('a')
    // 距底 50px：scrollTop = 1000-100-50 = 850
    userScrollTo(850)
    expect(screen.getByTestId('at-bottom')).toHaveTextContent('true')
  })
})
