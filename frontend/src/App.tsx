import { useEffect, useRef } from 'react'
import { Button, Switch, message } from 'antd'
import AppLayout from './components/AppLayout'
import SessionSidebar from './components/SessionSidebar'
import MessageList from './components/MessageList'
import EmptyState from './components/EmptyState'
import ChatInput from './components/ChatInput'
import InlineError from './components/InlineError'
import { useChatStream } from './hooks/useChatStream'
import { useSessions } from './hooks/useSessions'
import { useLocalDraft } from './hooks/useLocalDraft'
import { readStoredSessionId, writeStoredSessionId } from './utils/storage'

const STREAMING_BLOCK_MESSAGE = '生成中，请先停止'

export default function App() {
  const sessions = useSessions()
  const chat = useChatStream({ onSessionsChanged: sessions.refresh })
  // 草稿按会话作用域隔离（无当前会话时用 'global'）
  const { draft, setDraft } = useLocalDraft(chat.currentSessionId ?? 'global')

  // 刷新恢复：记忆模式下从 localStorage 读上次会话，拉历史回填；
  // 404（会话已删）静默回空态并清除记录；其他失败也不打断首屏。
  const recoveredRef = useRef(false)
  useEffect(() => {
    if (recoveredRef.current) return
    recoveredRef.current = true
    const sid = readStoredSessionId()
    if (!sid) return
    void (async () => {
      const res = await sessions.selectSession(sid)
      if (res.kind === 'ok') {
        chat.showHistory(sid, res.messages)
      } else if (res.kind === 'notfound') {
        writeStoredSessionId(null)
      }
    })()
    // 仅挂载时执行一次；hooks 引用均为稳定 useCallback
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 当前会话 ID 持久化：仅记忆模式写入；关闭记忆/新建会话时清除（无状态不留痕）
  useEffect(() => {
    writeStoredSessionId(chat.remember ? chat.currentSessionId : null)
  }, [chat.currentSessionId, chat.remember])

  /** 切换历史会话；流式中阻止（FR-15.3）。 */
  const handleSelect = async (id: string) => {
    if (chat.isStreaming) {
      message.warning(STREAMING_BLOCK_MESSAGE)
      return
    }
    const res = await sessions.selectSession(id)
    if (res.kind === 'ok') {
      chat.showHistory(id, res.messages)
    } else if (res.kind === 'notfound') {
      // 会话已被删除：侧边栏项已由 useSessions 移除，主区回空态（静默）
      chat.startNew()
    } else {
      message.error('会话加载失败，请重试')
    }
  }

  const handleNew = () => {
    if (chat.isStreaming) {
      message.warning(STREAMING_BLOCK_MESSAGE)
      return
    }
    chat.startNew()
  }

  const handleDelete = async (id: string) => {
    try {
      await sessions.removeSession(id)
      // 删的是当前会话 → 清空主区
      if (id === chat.currentSessionId) {
        chat.startNew()
      }
    } catch {
      message.error('会话删除失败，请重试')
    }
  }

  const handleRename = async (id: string, title: string) => {
    try {
      await sessions.renameSession(id, title)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重命名失败，请重试')
    }
  }

  const handleSend = (text: string) => {
    void chat.send(text)
    setDraft('')
  }

  const handlePickExample = (prompt: string) => {
    if (chat.isStreaming) {
      message.warning(STREAMING_BLOCK_MESSAGE)
      return
    }
    void chat.send(prompt)
  }

  return (
    <AppLayout
      sidebar={
        <SessionSidebar
          sessions={sessions.sessions}
          loading={sessions.loading}
          loadError={sessions.loadError}
          currentSessionId={chat.currentSessionId}
          onSelect={(id) => void handleSelect(id)}
          onNew={handleNew}
          onDelete={handleDelete}
          onRename={handleRename}
          onRetry={sessions.refresh}
        />
      }
      headerExtra={
        <>
          <label className="remember-switch">
            <Switch checked={chat.remember} onChange={chat.setRemember} data-testid="remember-switch" />
            <span>记住本次对话</span>
          </label>
          {/* 管理控制台入口：仅写 hash，不 import admin 模块、不发请求（AC-1/50 边界） */}
          <Button
            size="small"
            data-testid="admin-entry-btn"
            onClick={() => {
              window.location.hash = '#/admin'
            }}
          >
            管理控制台
          </Button>
        </>
      }
    >
      <div className="chat-main">
        {chat.messages.length === 0 ? (
          <div className="empty-state-wrap">
            <EmptyState onPick={handlePickExample} />
          </div>
        ) : (
          <MessageList messages={chat.messages} />
        )}
      </div>
      <InlineError error={chat.lastError} onClose={chat.dismissError} />
      <ChatInput
        value={draft}
        onChange={setDraft}
        streaming={chat.isStreaming}
        onSend={handleSend}
        onStop={chat.stop}
      />
    </AppLayout>
  )
}
