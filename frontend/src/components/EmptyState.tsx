// 空会话欢迎区：示例问题卡片，点击后作为首轮消息发送（FR-12）。
export const EXAMPLE_PROMPTS = [
  '用 Spring Boot 写一个 SSE 服务端推送的最小示例',
  '解释一下 MySQL InnoDB 的 MVCC 机制',
  '帮我写一首关于秋天傍晚的短诗',
]

export default function EmptyState({ onPick }: { onPick: (prompt: string) => void }) {
  return (
    <div className="empty-state" data-testid="empty-state">
      <h2 className="empty-title">你好，我是 AI 对话助手</h2>
      <p className="empty-subtitle">输入问题开始对话，或试试下面的示例：</p>
      <div className="example-list">
        {EXAMPLE_PROMPTS.map((prompt, i) => (
          <button
            key={prompt}
            type="button"
            className="example-card"
            data-testid={`example-card-${i}`}
            onClick={() => onPick(prompt)}
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  )
}
