import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import SessionSidebar from './SessionSidebar'
import type { SessionSummary } from '../types'

const SID = '123e4567-e89b-12d3-a456-426614174000'
const OTHER = '223e4567-e89b-12d3-a456-426614174001'

function session(id: string, overrides: Partial<SessionSummary> = {}): SessionSummary {
  return {
    sessionId: id,
    title: '标题A',
    createdAt: '2026-09-01 10:00:00',
    updatedAt: '2026-09-03 14:30:00',
    previewRole: 'assistant',
    previewText: '预览内容',
    ...overrides,
  }
}

const baseProps = {
  loading: false,
  loadError: false,
  currentSessionId: null as string | null,
  onSelect: vi.fn(),
  onNew: vi.fn(),
  onDelete: vi.fn().mockResolvedValue(undefined),
  onRename: vi.fn().mockResolvedValue(undefined),
  onRetry: vi.fn(),
}

function renderSidebar(sessions: SessionSummary[], props: Partial<typeof baseProps> = {}) {
  return render(<SessionSidebar sessions={sessions} {...baseProps} {...props} />)
}

describe('SessionSidebar', () => {
  it('渲染列表：title null 兜底「新会话」，有预览行', () => {
    renderSidebar([session(SID), session(OTHER, { title: null, previewText: null })])
    const list = screen.getByTestId('session-list')
    expect(within(list).getByText('标题A')).toBeInTheDocument()
    // 空标题项在列表内兜底显示「新会话」（与顶部「新会话」按钮区分）
    expect(within(list).getByText('新会话')).toBeInTheDocument()
    expect(within(list).getByText('预览内容')).toBeInTheDocument()
    // 无预览的会话不渲染预览行
    expect(screen.queryAllByText('预览内容')).toHaveLength(1)
  })

  it('点击会话项触发 onSelect', async () => {
    const onSelect = vi.fn()
    renderSidebar([session(SID)], { onSelect })
    await userEvent.click(screen.getByTestId(`session-select-${SID}`))
    expect(onSelect).toHaveBeenCalledWith(SID)
  })

  it('新会话按钮触发 onNew', async () => {
    const onNew = vi.fn()
    renderSidebar([], { onNew })
    await userEvent.click(screen.getByTestId('new-session-btn'))
    expect(onNew).toHaveBeenCalled()
  })

  it('加载中显示骨架；失败显示重试按钮', () => {
    const { rerender } = renderSidebar([], { loading: true })
    expect(screen.getByTestId('sidebar-skeleton')).toBeInTheDocument()
    rerender(<SessionSidebar sessions={[]} {...baseProps} loading={false} loadError={true} />)
    fireEvent.click(screen.getByTestId('sidebar-retry-btn'))
    expect(baseProps.onRetry).toHaveBeenCalled()
  })

  it('删除：Popconfirm 确认后调用 onDelete', async () => {
    const onDelete = vi.fn().mockResolvedValue(undefined)
    renderSidebar([session(SID)], { onDelete })
    await userEvent.click(screen.getByTestId(`delete-btn-${SID}`))
    // Popconfirm 二次确认文案
    expect(await screen.findByText('确定删除该会话及其全部消息？')).toBeInTheDocument()
    // 弹出后点确认
    const confirmBtn = await screen.findByTestId(`delete-confirm-${SID}`)
    await userEvent.click(confirmBtn)
    await waitFor(() => expect(onDelete).toHaveBeenCalledWith(SID))
  })

  it('重命名：弹窗回填当前标题，保存调用 onRename（trim 值）', async () => {
    const onRename = vi.fn().mockResolvedValue(undefined)
    renderSidebar([session(SID)], { onRename })
    await userEvent.click(screen.getByTestId(`rename-btn-${SID}`))
    const input = await screen.findByTestId('rename-input')
    expect((input as HTMLInputElement).value).toBe('标题A')
    await userEvent.clear(input)
    await userEvent.type(input, '  新的标题  ')
    await userEvent.click(screen.getByTestId('rename-ok'))
    await waitFor(() => expect(onRename).toHaveBeenCalledWith(SID, '新的标题'))
  })

  it('重命名：空白标题前端拦截（显示错误，不调 onRename）', async () => {
    const onRename = vi.fn().mockResolvedValue(undefined)
    renderSidebar([session(SID)], { onRename })
    await userEvent.click(screen.getByTestId(`rename-btn-${SID}`))
    const input = await screen.findByTestId('rename-input')
    await userEvent.clear(input)
    await userEvent.type(input, '   ')
    await userEvent.click(screen.getByTestId('rename-ok'))
    expect(await screen.findByTestId('rename-error')).toBeInTheDocument()
    expect(onRename).not.toHaveBeenCalled()
  })
})
