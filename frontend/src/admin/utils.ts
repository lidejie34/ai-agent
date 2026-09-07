import dayjs from 'dayjs'

// 管理端展示工具（迭代 H，AC-51）：时间统一 dayjs 宽解析后端 yyyy-MM-dd HH:mm:ss，不手拆字符串。

/** 时间格式化：无效/空值兜底「-」。 */
export function fmtTime(s?: string | null): string {
  if (!s) return '-'
  const d = dayjs(s)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : '-'
}

/** 文本兜底：空白/空值显示「-」。 */
export function dash(v?: string | null): string {
  return v && String(v).trim() ? v : '-'
}

/** 审计耗时展示：<1000ms 显示 N ms；≥1000ms 显示 N.NN s（D4）。 */
export function fmtDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '-'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

/** 判定抛出物是否为带 code 的错误（ApiError/NetworkError 均满足）。 */
export function hasErrorCode(e: unknown): e is { code: string; message?: string } {
  return !!e && typeof e === 'object' && typeof (e as { code?: unknown }).code === 'string'
}
