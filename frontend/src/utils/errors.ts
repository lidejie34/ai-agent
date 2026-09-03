import type { ApiError } from '../types'

// 错误码 → 用户可读文案（FR-17.2 差异化引导）。
export function errorCodeToText(code: string | undefined, fallback?: string): string {
  switch (code) {
    case 'MEMORY_UNAVAILABLE':
      return '会话记忆暂不可用，可关闭「记住本次对话」以无状态继续'
    case 'ARK_NOT_CONFIGURED':
      return '后端未配置模型密钥，请联系管理员'
    case 'SESSION_NOT_FOUND':
      return '会话已不存在'
    case 'BAD_REQUEST':
      return '请求异常，可刷新页面或新建会话'
    case 'MODEL_CALL_FAILED':
      return '生成失败，可重试'
    case 'NETWORK_ERROR':
      return '网络连接中断，请检查网络后重试'
    case 'WATCHDOG_TIMEOUT':
      return '连接超时（长时间无响应），已停止生成，可重试'
    default:
      return fallback && fallback.trim() ? fallback : '生成失败/连接中断，可重试'
  }
}

export function apiErrorText(error: ApiError | { code: string; message?: string }): string {
  return errorCodeToText(error.code, error.message)
}
