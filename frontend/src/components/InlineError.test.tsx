import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { message } from 'antd'
import InlineError from './InlineError'
import type { ApiError, NetworkError } from '../types'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('InlineError', () => {
  it('无错误时不渲染', () => {
    const { container } = render(<InlineError error={null} onClose={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('ApiError：Alert 展示按错误码映射的文案，并弹一次 toast', () => {
    const toast = vi.spyOn(message, 'error').mockImplementation(() => 'ignored' as never)
    const error: ApiError = {
      code: 'ARK_NOT_CONFIGURED',
      message: 'ark api key not configured',
      timestamp: '2026-09-03 15:00:00',
    }
    render(<InlineError error={error} onClose={() => {}} />)
    const alert = screen.getByTestId('inline-error')
    expect(alert).toHaveTextContent('后端未配置模型密钥，请联系管理员')
    expect(toast).toHaveBeenCalledTimes(1)
    expect(toast.mock.calls[0][0]).toContain('后端未配置模型密钥')
  })

  it('NetworkError（看门狗超时）展示超时文案', () => {
    vi.spyOn(message, 'error').mockImplementation(() => 'ignored' as never)
    const error: NetworkError = { code: 'WATCHDOG_TIMEOUT', message: 'timeout' }
    render(<InlineError error={error} onClose={() => {}} />)
    expect(screen.getByTestId('inline-error')).toHaveTextContent('连接超时')
  })

  it('关闭 Alert 触发 onClose', () => {
    vi.spyOn(message, 'error').mockImplementation(() => 'ignored' as never)
    const onClose = vi.fn()
    const error: ApiError = { code: 'BAD_REQUEST', message: 'bad', timestamp: '' }
    render(<InlineError error={error} onClose={onClose} />)
    // antd Alert closable 的关闭按钮
    const closeBtn = screen.getByRole('button', { name: /close/i })
    closeBtn.click()
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
