import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useHashRoute } from './useHashRoute'

// T0：hash 路由解析（AC-1/2/3/4）。afterEach 清 hash 隔离。
beforeEach(() => {
  window.location.hash = ''
})
afterEach(() => {
  window.location.hash = ''
})

describe('useHashRoute', () => {
  it('无 hash → isAdmin=false（对话页）', () => {
    const { result } = renderHook(() => useHashRoute())
    expect(result.current.isAdmin).toBe(false)
  })

  it('#/admin → isAdmin=true，默认 page=tools（AC-4 默认子页）', () => {
    window.location.hash = '#/admin'
    const { result } = renderHook(() => useHashRoute())
    expect(result.current.isAdmin).toBe(true)
    expect(result.current.page).toBe('tools')
  })

  it('#/admin/tools|mcp|logs|kb 解析为对应子页（AC-4）', () => {
    const cases: Array<[string, 'tools' | 'mcp' | 'logs' | 'kb']> = [
      ['#/admin/tools', 'tools'],
      ['#/admin/mcp', 'mcp'],
      ['#/admin/logs', 'logs'],
      ['#/admin/kb', 'kb'],
    ]
    for (const [hash, page] of cases) {
      window.location.hash = hash
      const { result, unmount } = renderHook(() => useHashRoute())
      expect(result.current.isAdmin).toBe(true)
      expect(result.current.page).toBe(page)
      unmount()
    }
  })

  it('未知 #/admin/xxx 回落默认子页 tools（AC-2）', () => {
    window.location.hash = '#/admin/whatever'
    const { result } = renderHook(() => useHashRoute())
    expect(result.current.isAdmin).toBe(true)
    expect(result.current.page).toBe('tools')
  })

  it('hashchange 事件驱动路由更新（刷新/直达/浏览器前进后退）', async () => {
    const { result } = renderHook(() => useHashRoute())
    expect(result.current.isAdmin).toBe(false)
    act(() => {
      window.location.hash = '#/admin/mcp'
    })
    // jsdom 的 hashchange 为宏任务异步派发，waitFor 等待状态更新
    await waitFor(() => expect(result.current.isAdmin).toBe(true))
    expect(result.current.page).toBe('mcp')
  })

  it('navigate(to) 修改 hash 并驱动更新（Tabs 联动，AC-4）', async () => {
    const { result } = renderHook(() => useHashRoute())
    act(() => {
      result.current.navigate('/admin/logs')
    })
    expect(window.location.hash).toBe('#/admin/logs')
    await waitFor(() => expect(result.current.page).toBe('logs'))
  })

  it('backToChat() 清空 hash（URL 无残留 #）→ isAdmin=false（AC-3）', () => {
    window.location.hash = '#/admin/mcp'
    const { result } = renderHook(() => useHashRoute())
    expect(result.current.isAdmin).toBe(true)
    act(() => {
      result.current.backToChat()
    })
    expect(window.location.hash).toBe('')
    expect(result.current.isAdmin).toBe(false)
  })
})
