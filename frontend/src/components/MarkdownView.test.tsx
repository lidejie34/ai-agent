import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import MarkdownView from './MarkdownView'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('MarkdownView', () => {
  it('渲染 Markdown：段落、粗体、列表、GFM 表格', () => {
    const md = `# 标题\n\n这是 **粗体** 文本。\n\n- 项目一\n- 项目二\n\n| 列A | 列B |\n| --- | --- |\n| 1 | 2 |\n`
    const { container } = render(<MarkdownView content={md} />)
    expect(screen.getByText('标题')).toBeInTheDocument()
    expect(container.querySelector('strong')).toHaveTextContent('粗体')
    expect(container.querySelectorAll('li')).toHaveLength(2)
    // GFM 表格（remark-gfm）
    expect(container.querySelectorAll('table')).toHaveLength(1)
    expect(container.querySelector('table')).toHaveTextContent('列A')
  })

  it('代码块：高亮 class、复制按钮写入剪贴板', async () => {
    const writeText = vi.fn(async (_text: string) => {})
    Object.assign(navigator.clipboard, { writeText })
    const md = '说明：\n\n```js\nconst a = 1\nconsole.log(a)\n```\n'
    const { container } = render(<MarkdownView content={md} />)

    const code = container.querySelector('code.language-js')
    expect(code).not.toBeNull()
    // rehype-highlight 注入 hljs 标记
    expect(container.querySelector('.hljs')).not.toBeNull()

    const copyBtn = screen.getByRole('button', { name: /复制/ })
    fireEvent.click(copyBtn)
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    const copied = writeText.mock.calls[0][0] as string
    expect(copied).toContain('const a = 1')
    expect(copied).toContain('console.log(a)')
  })

  it('安全：不启用 rehype-raw，原始 HTML 不成为 DOM 元素（防 XSS）', () => {
    const md =
      '正文\n\n<img src="x" onerror="alert(1)" alt="xss">\n\n<script>alert(2)</script>\n\n[正常](https://example.com)'
    const { container } = render(<MarkdownView content={md} />)
    // 危险标签不进入 DOM（原始 HTML 被转义为纯文本，见 container 文本）
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    // 任何元素都不得携带 onerror 等事件属性
    expect(container.querySelector('[onerror]')).toBeNull()
    expect(container.textContent).toContain('onerror') // 仅作为转义文本存在
    // 正常链接保留
    expect(container.querySelector('a')?.getAttribute('href')).toBe('https://example.com')
  })

  it('行内代码正常渲染（不带复制按钮）', () => {
    const { container } = render(<MarkdownView content={'使用 `npm test` 运行测试'} />)
    expect(container.querySelector('code')).toHaveTextContent('npm test')
    expect(screen.queryByRole('button', { name: /复制/ })).toBeNull()
  })
})
