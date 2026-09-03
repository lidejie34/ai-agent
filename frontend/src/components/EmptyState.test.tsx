import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import EmptyState from './EmptyState'

describe('EmptyState', () => {
  it('展示欢迎语与 3 个示例问题卡片', () => {
    render(<EmptyState onPick={() => {}} />)
    expect(screen.getByTestId('empty-state')).toBeInTheDocument()
    const cards = screen.getAllByTestId(/^example-card-/)
    expect(cards).toHaveLength(3)
  })

  it('点击示例卡片回调对应文案（填入输入框/直接发送）', () => {
    const onPick = vi.fn()
    render(<EmptyState onPick={onPick} />)
    fireEvent.click(screen.getByTestId('example-card-0'))
    expect(onPick).toHaveBeenCalledTimes(1)
    const picked = onPick.mock.calls[0][0] as string
    expect(typeof picked).toBe('string')
    expect(picked.length).toBeGreaterThan(0)
    expect(screen.getByText(picked)).toBeInTheDocument()
  })
})
