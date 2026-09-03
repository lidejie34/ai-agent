import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// 每个用例后卸载组件，避免串扰
afterEach(() => {
  cleanup()
})

// ---- jsdom 缺失、antd 组件运行所需的浏览器 API polyfill（仅测试环境） ----

if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
}

if (typeof globalThis !== 'undefined' && !(globalThis as { ResizeObserver?: unknown }).ResizeObserver) {
  class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = ResizeObserverStub
}

if (typeof navigator !== 'undefined' && !navigator.clipboard) {
  Object.assign(navigator, {
    clipboard: { writeText: async (): Promise<void> => {} },
  })
}

// scrollIntoView 在 jsdom 中未实现
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView(): void {}
}

// 本机 Node 自带残缺的 localStorage 全局（--localstorage-file 未配置，
// 无 getItem/setItem/clear），会遮蔽 jsdom 的实现；用内存 Storage 替换。
if (
  typeof globalThis !== 'undefined' &&
  (!(globalThis as { localStorage?: Storage }).localStorage ||
    typeof (globalThis as { localStorage?: Storage }).localStorage?.clear !== 'function')
) {
  class MemoryStorage {
    private map = new Map<string, string>()
    getItem(key: string): string | null {
      return this.map.has(key) ? this.map.get(key)! : null
    }
    setItem(key: string, value: string): void {
      this.map.set(key, String(value))
    }
    removeItem(key: string): void {
      this.map.delete(key)
    }
    clear(): void {
      this.map.clear()
    }
    key(index: number): string | null {
      return Array.from(this.map.keys())[index] ?? null
    }
    get length(): number {
      return this.map.size
    }
  }
  const storage = new MemoryStorage() as unknown as Storage
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    enumerable: true,
    value: storage,
  })
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      enumerable: true,
      value: storage,
    })
  }
}
