// 标题/预览截断规则（与后端 TextTitleUtils 同规则同向量，AC-8）：
// trim → \s+ 折叠为单空格 → 按 code point 取前 N 个（代理对不切断）→ 超长加 …。

export const TITLE_LIMIT = 20
export const PREVIEW_LIMIT = 30

const ELLIPSIS = '…'

/** 按 code point 截断；null/undefined 入参返回空串。 */
export function truncateByCodePoints(raw: string | null | undefined, limit: number): string {
  if (raw == null) return ''
  const s = raw.trim().replace(/\s+/g, ' ')
  // 用迭代器（按 code point）取前 limit+1 个，判定是否超长
  const cps = Array.from(s).slice(0, limit + 1)
  if (cps.length <= limit) return s
  return cps.slice(0, limit).join('') + ELLIPSIS
}

/** 会话标题：首轮用户消息截断至 TITLE_LIMIT。 */
export function buildTitle(firstUserMessage: string | null | undefined): string {
  return truncateByCodePoints(firstUserMessage, TITLE_LIMIT)
}

/** 消息预览：截断至 PREVIEW_LIMIT。 */
export function buildPreview(content: string | null | undefined): string {
  return truncateByCodePoints(content, PREVIEW_LIMIT)
}

export const RENAME_MAX = 200

/** 重命名前端校验（FR-16.5，与后端 1–200 code point 同口径）：trim 后非空且不超长。 */
export function validateRenameTitle(
  raw: string,
): { ok: true; title: string } | { ok: false; error: string } {
  const title = (raw ?? '').trim()
  if (!title) return { ok: false, error: '标题不能为空' }
  if (Array.from(title).length > RENAME_MAX) {
    return { ok: false, error: `标题最长 ${RENAME_MAX} 个字符` }
  }
  return { ok: true, title }
}
