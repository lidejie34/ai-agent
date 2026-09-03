import { describe, it, expect } from 'vitest'
import { buildTitle, buildPreview, truncateByCodePoints, TITLE_LIMIT, PREVIEW_LIMIT } from './title'

// 与后端 TextTitleUtilsTest 共享的测试向量（同输入同期望，AC-8）。
describe('title 截断规则（前后端同向量）', () => {
  it('null/undefined 返回空串', () => {
    expect(truncateByCodePoints(null, 20)).toBe('')
    expect(buildTitle(undefined)).toBe('')
    expect(buildPreview(null)).toBe('')
  })

  it('trim 并折叠换行/连续空白，未超长不加省略号', () => {
    expect(buildTitle('  你好   世界\n你好 ')).toBe('你好 世界 你好')
  })

  it('tab/CRLF/多空格混合全部折叠为单空格', () => {
    expect(truncateByCodePoints('a\tb\r\nc   d\te', 20)).toBe('a b c d e')
  })

  it('恰好 20 个 code point 原样、无省略号', () => {
    const twenty = '汉'.repeat(20)
    expect(buildTitle(twenty)).toBe(twenty)
    expect(buildTitle(twenty)).not.toContain('…')
  })

  it('21 个汉字 → 前 20 + …', () => {
    expect(buildTitle('汉'.repeat(21))).toBe('汉'.repeat(20) + '…')
  })

  it('emoji 算 1 个 code point：15 汉字 + 5 emoji = 20 cp 原样', () => {
    const input = '我喜欢😀'.repeat(5)
    expect(input.length).toBe(25) // UTF-16 char 数
    expect(Array.from(input).length).toBe(20) // code point 数
    expect(buildTitle(input)).toBe(input)
  })

  it('25 cp（含 emoji，边界切到 emoji）→ 前 20 cp + …，不切断代理对', () => {
    const input = '汉'.repeat(19) + '😀'.repeat(6) // 19 + 6 = 25 cp
    const r = buildTitle(input)
    expect(r).toBe('汉'.repeat(19) + '😀' + '…')
    // 省略号前一个 code point 是完整 emoji
    const body = r.slice(0, -1)
    expect(Array.from(body).length).toBe(20)
    expect(Array.from(body)[19]).toBe('😀')
  })

  it('预览 limit=30：31 cp → 30 + …，30 cp 原样', () => {
    expect(buildPreview('字'.repeat(31))).toBe('字'.repeat(30) + '…')
    expect(buildPreview('字'.repeat(30))).toBe('字'.repeat(30))
  })

  it('常量钉死 20/30', () => {
    expect(TITLE_LIMIT).toBe(20)
    expect(PREVIEW_LIMIT).toBe(30)
  })
})
