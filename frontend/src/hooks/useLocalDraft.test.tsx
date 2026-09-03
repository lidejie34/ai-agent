import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useLocalDraft } from './useLocalDraft'
import { DRAFT_KEY_PREFIX, readDraft, writeDraft } from '../utils/storage'

beforeEach(() => {
  localStorage.clear()
})

describe('useLocalDraft', () => {
  it('草稿写入 localStorage；卸载后重挂载同一会话作用域可恢复', () => {
    const first = renderHook(({ scope }: { scope: string }) => useLocalDraft(scope), {
      initialProps: { scope: 'global' },
    })
    act(() => first.result.current.setDraft('未发送的内容'))
    expect(readDraft('global')).toBe('未发送的内容')
    expect(localStorage.getItem(`${DRAFT_KEY_PREFIX}global`)).toBe('未发送的内容')
    first.unmount()

    // 模拟刷新：同作用域新 hook 实例恢复草稿
    const second = renderHook(() => useLocalDraft('global'))
    expect(second.result.current.draft).toBe('未发送的内容')
  })

  it('不同会话作用域草稿互不干扰（切换会话恢复各自草稿）', () => {
    localStorage.setItem(`${DRAFT_KEY_PREFIX}sess-a`, 'A 的草稿')
    localStorage.setItem(`${DRAFT_KEY_PREFIX}sess-b`, 'B 的草稿')

    const hook = renderHook(({ scope }: { scope: string }) => useLocalDraft(scope), {
      initialProps: { scope: 'sess-a' },
    })
    expect(hook.result.current.draft).toBe('A 的草稿')

    hook.rerender({ scope: 'sess-b' })
    expect(hook.result.current.draft).toBe('B 的草稿')

    hook.rerender({ scope: 'sess-a' })
    expect(hook.result.current.draft).toBe('A 的草稿')
  })

  it('发送后清空草稿（空串移除 storage 键）', () => {
    const { result } = renderHook(() => useLocalDraft('global'))
    act(() => result.current.setDraft('待发送'))
    expect(readDraft('global')).toBe('待发送')

    act(() => result.current.setDraft(''))
    expect(result.current.draft).toBe('')
    expect(localStorage.getItem(`${DRAFT_KEY_PREFIX}global`)).toBeNull()
  })

  it('writeDraft 工具：空值移除键', () => {
    writeDraft('global', 'x')
    expect(readDraft('global')).toBe('x')
    writeDraft('global', '')
    expect(readDraft('global')).toBe('')
  })
})
