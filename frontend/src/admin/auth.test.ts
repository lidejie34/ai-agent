import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  clearAdminToken,
  getAdminToken,
  notifyUnauthorized,
  onUnauthorized,
  persistAdminToken,
  stageToken,
} from './auth'

// T1：token 仓（内存优先 + localStorage admin.token + 401 广播，AC-11/12/13/14）
const KEY = 'admin.token'

beforeEach(() => {
  clearAdminToken()
  localStorage.removeItem(KEY)
})
afterEach(() => {
  clearAdminToken()
  localStorage.removeItem(KEY)
  vi.restoreAllMocks()
})

describe('admin token 仓（auth.ts）', () => {
  it('persistAdminToken 后内存与 localStorage(键 admin.token) 均可读（AC-7）', () => {
    persistAdminToken('tok-abc')
    expect(getAdminToken()).toBe('tok-abc')
    expect(localStorage.getItem(KEY)).toBe('tok-abc')
  })

  it('clearAdminToken 后内存与 localStorage 均清空（AC-12）', () => {
    persistAdminToken('tok-abc')
    clearAdminToken()
    expect(getAdminToken()).toBeNull()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('localStorage.setItem 抛错时静默降级：token 仅内存有效、无异常（AC-14/D9）', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(() => persistAdminToken('tok-mem')).not.toThrow()
    expect(localStorage.getItem(KEY)).toBeNull() // 未落盘
    expect(getAdminToken()).toBe('tok-mem') // 内存态仍有效
  })

  it('内存态为空时从 localStorage 读取（刷新后恢复登录态，AC-3）', () => {
    // 模拟刷新：内存已清（clearAdminToken），仅盘上有 token
    localStorage.setItem(KEY, 'tok-disk')
    expect(getAdminToken()).toBe('tok-disk')
  })

  it('stageToken 只存内存不落盘（登录验证期，AC-8）', () => {
    stageToken('tok-stage')
    expect(getAdminToken()).toBe('tok-stage')
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('onUnauthorized 订阅/退订；notifyUnauthorized 触发订阅者（AC-11 广播）', () => {
    const fn = vi.fn()
    const unsub = onUnauthorized(fn)
    notifyUnauthorized()
    expect(fn).toHaveBeenCalledTimes(1)
    unsub()
    notifyUnauthorized()
    expect(fn).toHaveBeenCalledTimes(1) // 退订后不再触发
  })
})
