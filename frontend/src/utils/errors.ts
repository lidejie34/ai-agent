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

/**
 * 管理端错误文案（迭代 H，AC-46）：已知管理码固定中文引导；
 * 校验类 400（BAD_REQUEST）透传后端 message 原文（含字段名）；网络错误用网络文案。
 * 独立于 errorCodeToText——既有 switch 的 BAD_REQUEST 固定文案会遮蔽校验 message 透传，
 * 故对话路径函数一字不动，管理端单独映射。
 */
export function adminErrorText(e: { code: string; message?: string }): string {
  switch (e.code) {
    case 'ADMIN_NOT_CONFIGURED':
      return '服务端未配置管理令牌，请联系运维配置 APP_ADMIN_TOKEN'
    case 'TOOLS_DISABLED':
      return '工具功能未启用（app.tools.enabled=false），管理控制台暂不可用'
    case 'ADMIN_UNAUTHORIZED':
      return '登录已失效，请重新登录'
    case 'TOOL_NOT_FOUND':
      return '工具不存在或已被删除'
    case 'TOOLS_UNAVAILABLE':
      return '工具服务暂不可用（数据库访问失败），请稍后重试'
    case 'NETWORK_ERROR':
      return '网络连接中断，请检查网络后重试'
    default:
      // BAD_REQUEST 等校验错误：透传后端中文 message（含字段名）；无 message 时兜底
      return e.message && e.message.trim() ? e.message : '请求失败，请稍后重试'
  }
}
