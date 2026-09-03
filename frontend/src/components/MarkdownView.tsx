import { isValidElement, useRef, useState, type ReactNode, type HTMLAttributes } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'

// 安全红线：只加 remark-gfm / rehype-highlight，**禁止 rehype-raw**。
// 模型输出中的原始 HTML（<img onerror>、<script> 等）不会被解析成 DOM 元素。

/** 递归取 React 子树的纯文本（用于复制代码块内容）。 */
function nodeText(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(nodeText).join('')
  if (isValidElement(node)) {
    return nodeText((node.props as { children?: ReactNode }).children)
  }
  return ''
}

function CodeBlock({ children }: HTMLAttributes<HTMLPreElement>) {
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const handleCopy = async () => {
    const text = nodeText(children).replace(/\n$/, '')
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => setCopied(false), 1500)
    } catch {
      // 剪贴板不可用（权限/非安全上下文）时静默失败，不影响阅读
    }
  }

  return (
    <div className="code-block">
      <button
        type="button"
        className="code-copy-btn"
        onClick={handleCopy}
        data-testid="code-copy-btn"
      >
        {copied ? '已复制' : '复制'}
      </button>
      <pre>{children}</pre>
    </div>
  )
}

export default function MarkdownView({ content }: { content: string }) {
  return (
    <div className="markdown-body" data-testid="markdown-view">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
