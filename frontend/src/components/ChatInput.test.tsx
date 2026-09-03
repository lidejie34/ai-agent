import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { useState } from 'react'
import ChatInput from './ChatInput'

function Harness(props: { streaming?: boolean; onSend?: (t: string) => void; onStop?: () => void }) {
  const [value, setValue] = useState('')
  return (
    <ChatInput
      value={value}
      onChange={setValue}
      streaming={props.streaming ?? false}
      onSend={props.onSend ?? (() => {})}
      onStop={props.onStop ?? (() => {})}
    />
  )
}

function typeText(text: string) {
  fireEvent.change(screen.getByTestId('chat-input'), { target: { value: text } })
}

function pressEnter(init?: KeyboardEventInit) {
  fireEvent.keyDown(screen.getByTestId('chat-input'), { key: 'Enter', ...init })
}

describe('ChatInput', () => {
  it('Enter 发送并清空输入；空白内容不发送（按钮禁用）', () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)

    // 空白：发送按钮禁用
    expect(screen.getByTestId('chat-send')).toBeDisabled()
    pressEnter()
    expect(onSend).not.toHaveBeenCalled()

    typeText('  你好  ')
    expect(screen.getByTestId('chat-send')).toBeEnabled()
    pressEnter()
    expect(onSend).toHaveBeenCalledWith('  你好  ')
    // 发送后清空（trim 由发送方/ hook 负责，输入框回到空）
    expect((screen.getByTestId('chat-input') as HTMLTextAreaElement).value).toBe('')
  })

  it('Shift+Enter 换行不发送', () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)
    typeText('第一行')
    pressEnter({ shiftKey: true })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('中文输入法组词期间 Enter 不发送；组词结束后 Enter 发送', () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)
    const textarea = screen.getByTestId('chat-input')
    typeText('你好')

    // compositionstart → Enter 应被吞掉（组词确认）
    fireEvent.compositionStart(textarea)
    pressEnter()
    expect(onSend).not.toHaveBeenCalled()

    // compositionend 之后 Enter 正常发送
    fireEvent.compositionEnd(textarea)
    pressEnter()
    expect(onSend).toHaveBeenCalledTimes(1)
  })

  it('流式中：发送按钮禁用，出现停止按钮；Enter 不发送；停止回调触发', () => {
    const onSend = vi.fn()
    const onStop = vi.fn()
    render(<Harness streaming onSend={onSend} onStop={onStop} />)
    typeText('还在生成时输入')

    expect(screen.queryByTestId('chat-send')).toBeNull()
    const stopBtn = screen.getByTestId('chat-stop')
    expect(stopBtn).toBeEnabled()
    pressEnter()
    expect(onSend).not.toHaveBeenCalled()

    fireEvent.click(stopBtn)
    expect(onStop).toHaveBeenCalledTimes(1)
  })
})
