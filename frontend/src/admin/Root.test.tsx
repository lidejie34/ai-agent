import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Root from './Root'

// T0：顶层切换。对话页 <App/> 常驻挂载，admin 激活时 CSS 隐藏（AC-1/3/50）。
beforeEach(() => {
  window.location.hash = ''
  // App 挂载会拉 /api/sessions；stub 为空列表，避免真实网络。
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.includes('/api/sessions')) {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('not found', { status: 404 })
    }),
  )
})
afterEach(() => {
  window.location.hash = ''
  vi.unstubAllGlobals()
})

describe('Root（对话页 / 管理控制台切换）', () => {
  it('无 hash 时渲染对话页、不挂载控制台；顶栏有「管理控制台」入口（AC-1）', () => {
    render(<Root />)
    expect(screen.getByTestId('chat-root')).toBeInTheDocument()
    expect(screen.queryByTestId('admin-console')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '管理控制台' })).toBeInTheDocument()
  })

  it('点入口 → hash=#/admin、控制台挂载、对话页仅 CSS 隐藏不卸载（AC-1）', async () => {
    render(<Root />)
    await userEvent.click(screen.getByRole('button', { name: '管理控制台' }))
    await waitFor(() => expect(window.location.hash).toBe('#/admin'))
    expect(await screen.findByTestId('admin-console')).toBeInTheDocument()
    expect(screen.getByTestId('chat-root')).toHaveStyle({ display: 'none' })
  })

  it('对话页草稿在切到控制台再返回后仍在（App 常驻挂载，AC-1/3）', async () => {
    render(<Root />)
    const input = screen.getByTestId('chat-input')
    await userEvent.type(input, '草稿内容XYZ')
    await userEvent.click(screen.getByRole('button', { name: '管理控制台' }))
    expect(await screen.findByTestId('admin-console')).toBeInTheDocument()

    // 返回对话：清空 hash
    window.location.hash = ''
    await waitFor(() => expect(screen.queryByTestId('admin-console')).not.toBeInTheDocument())
    expect(screen.getByTestId('chat-root')).not.toHaveStyle({ display: 'none' })
    expect(screen.getByTestId('chat-input')).toHaveValue('草稿内容XYZ')
  })
})
