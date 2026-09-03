import { useRef } from 'react'
import { Button, Input } from 'antd'

export interface ChatInputProps {
  value: string
  onChange: (value: string) => void
  onSend: (text: string) => void
  onStop: () => void
  /** 流式中：发送禁用，显示「停止」按钮（FR-15.2）。 */
  streaming: boolean
}

export default function ChatInput({ value, onChange, onSend, onStop, streaming }: ChatInputProps) {
  // 中文输入法组词期间（compositionstart~compositionend）Enter 是选词，不发送
  const composingRef = useRef(false)

  const submit = () => {
    if (streaming) return
    if (!value.trim()) return // 空白拦截
    onSend(value)
    onChange('') // 发送后清空（草稿持久化层在 App 侧同步清除）
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      e.key === 'Enter' &&
      !e.shiftKey &&
      !composingRef.current &&
      !e.nativeEvent.isComposing // 部分浏览器 keyCode 229 场景兜底
    ) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <div className="chat-input-bar" data-testid="chat-input-bar">
      <Input.TextArea
        data-testid="chat-input"
        className="chat-input-textarea"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        onCompositionStart={() => {
          composingRef.current = true
        }}
        onCompositionEnd={() => {
          composingRef.current = false
        }}
        autoSize={{ minRows: 1, maxRows: 6 }}
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
      />
      {streaming ? (
        <Button danger data-testid="chat-stop" onClick={onStop}>
          停止
        </Button>
      ) : (
        <Button
          type="primary"
          data-testid="chat-send"
          disabled={!value.trim()}
          onClick={submit}
        >
          发送
        </Button>
      )}
    </div>
  )
}
