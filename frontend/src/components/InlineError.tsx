import { useEffect, useRef } from 'react'
import { Alert, message } from 'antd'
import { errorCodeToText } from '../utils/errors'
import type { ApiError, NetworkError } from '../types'

export interface InlineErrorProps {
  error: ApiError | NetworkError | null
  onClose: () => void
}

/**
 * 错误条：消息区顶部常驻 Alert（可关闭）+ 一次性 toast（FR-17.2）。
 * 文案按错误码映射（见 utils/errors），同一错误不重复弹 toast。
 */
export default function InlineError({ error, onClose }: InlineErrorProps) {
  const text = error ? errorCodeToText(error.code, (error as ApiError).message) : ''
  const toastKeyRef = useRef<string | null>(null)

  useEffect(() => {
    if (!error) {
      toastKeyRef.current = null
      return
    }
    const key = `${error.code}:${(error as ApiError).message ?? ''}`
    if (toastKeyRef.current !== key) {
      toastKeyRef.current = key
      message.error(text)
    }
  }, [error, text])

  if (!error) return null

  return (
    <Alert
      className="inline-error"
      data-testid="inline-error"
      type="error"
      showIcon
      closable
      onClose={onClose}
      message={text}
    />
  )
}
