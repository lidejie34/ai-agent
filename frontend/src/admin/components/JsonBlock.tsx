import { Button, message } from 'antd'

// JSON 缩进只读展示 + 一键复制（迭代 H，AC-25/51）；null/undefined 兜底「-」。
export default function JsonBlock({ value }: { value: Record<string, unknown> | null | undefined }) {
  if (value === null || value === undefined) {
    return <span className="admin-text-empty">-</span>
  }
  const text = JSON.stringify(value, null, 2)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制')
    } catch {
      message.error('复制失败，请手动选择复制')
    }
  }
  return (
    <div className="admin-jsonblock">
      <pre className="admin-json">{text}</pre>
      <Button size="small" onClick={copy}>
        复制
      </Button>
    </div>
  )
}
