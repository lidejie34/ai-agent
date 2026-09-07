import { Button, message } from 'antd'

// 长文本纯文本展示（迭代 H，AC-51）：<pre> 等宽自动换行 + 可选复制；
// 绝不渲染 markdown/HTML（guideMd/errorMessage/lastError/description 全文均走此组件）。

interface TextBlockProps {
  text?: string | null
  /** 空态文案（「无指南」「无错误信息」等），默认「-」 */
  empty?: string
  copyable?: boolean
}

export default function TextBlock({ text, empty = '-', copyable = true }: TextBlockProps) {
  const value = text ?? ''
  if (!value.trim()) {
    return <span className="admin-text-empty">{empty}</span>
  }
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      message.success('已复制')
    } catch {
      message.error('复制失败，请手动选择复制')
    }
  }
  return (
    <div className="admin-textblock">
      <pre className="admin-prewrap">{value}</pre>
      {copyable && (
        <Button size="small" className="admin-textblock-copy" onClick={copy}>
          复制
        </Button>
      )}
    </div>
  )
}
