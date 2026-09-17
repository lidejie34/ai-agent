import { useEffect, useRef, useState } from 'react'
import { Button, Dropdown, Modal, Switch, message } from 'antd'
import AppLayout from './components/AppLayout'
import SessionSidebar from './components/SessionSidebar'
import MessageList from './components/MessageList'
import EmptyState from './components/EmptyState'
import ChatInput from './components/ChatInput'
import InlineError from './components/InlineError'
import type { ChatMessage } from './types'
import KbFilterBar, { type KbFilterValue } from './components/KbFilterBar'
import ToolScopeBar, { type ToolScopeValue } from './components/ToolScopeBar'
import { clearContext, deleteTurn, getSessionScope, putSessionScope, truncateMessages, type SessionScope } from './api/sessions'
import { useChatStream } from './hooks/useChatStream'
import { useAvailableTools } from './hooks/useAvailableTools'
import { useKbDimensions } from './hooks/useKbDimensions'
import { useSessions } from './hooks/useSessions'
import { useLocalDraft } from './hooks/useLocalDraft'
import { readStoredSessionId, writeStoredSessionId } from './utils/storage'
import { DB_TOOLS_NONE, KB_NONE, MCP_NONE } from './scopeNone'

const STREAMING_BLOCK_MESSAGE = '生成中，请先停止'

export default function App() {
  const sessions = useSessions()
  const chat = useChatStream({ onSessionsChanged: sessions.refresh })
  // 草稿按会话作用域隔离（无当前会话时用 'global'）
  const { draft, setDraft } = useLocalDraft(chat.currentSessionId ?? 'global')
  // 知识库维度过滤（迭代10 追加）：选择器选项来自 /api/kb/dimensions，
  // 维度不可用（RAG 关/未维护）时选择器整体隐藏；值为页面级状态，随每轮发送
  const kbDims = useKbDimensions()
  const [kbFilter, setKbFilter] = useState<KbFilterValue>({ projects: [], tags: [] })
  // 对话级工具范围（迭代12）：选择器选项来自 /api/tools/available，
  // 工具不可用（开关关/无 MCP server）时选择器整体隐藏；随每轮发送 + 会话级持久化
  const toolOpts = useAvailableTools()
  const [toolScope, setToolScope] = useState<ToolScopeValue>({ enabled: true })

  // ---- 会话级范围配置（迭代12）：恢复 + 防抖保存 ----
  // 保存只在用户主动改选择器时触发（onChange），恢复（回填）绝不回写，避免 PUT 回环；
  // 首轮绑定由后端从请求体 best-effort upsert 兜底（新会话尚无 sessionId 可 PUT）。
  const sessionIdRef = useRef<string | null>(null)
  sessionIdRef.current = chat.currentSessionId
  const scopeSaveTimerRef = useRef<number | undefined>(undefined)

  /** 页面态 → PUT 请求体三态映射：空数组 KB 维度 → null（默认全部）；开关关 → 显式 []（全不挂）；
   *  「都不加载」哨兵 → 显式 []（KB 都不加载时 tags 归 null——选择器已禁用清空）。 */
  const toScopeBody = (kb: KbFilterValue, ts: ToolScopeValue): SessionScope => {
    const kbNone = kb.projects.includes(KB_NONE)
    return {
      kbProjects: kbNone ? [] : kb.projects.length > 0 ? kb.projects : null,
      kbTags: kbNone ? null : kb.tags.length > 0 ? kb.tags : null,
      toolNames: !ts.enabled ? [] : ts.toolNames?.includes(DB_TOOLS_NONE) ? [] : (ts.toolNames ?? null),
      mcpServers: !ts.enabled ? [] : ts.mcpServers?.includes(MCP_NONE) ? [] : (ts.mcpServers ?? null),
    }
  }

  const persistScopeDebounced = (kb: KbFilterValue, ts: ToolScopeValue) => {
    window.clearTimeout(scopeSaveTimerRef.current)
    scopeSaveTimerRef.current = window.setTimeout(() => {
      const sid = sessionIdRef.current
      if (!sid) return // 新会话未建：由首轮请求体 upsert 绑定，不落 PUT
      void putSessionScope(sid, toScopeBody(kb, ts)).catch(() => {
        // best-effort：保存失败不打断对话，下轮请求体仍会带最新范围
      })
    }, 400)
  }

  /** 切换到某会话后回填其持久化范围；失败静默（保持页面当前值）。 */
  const restoreScope = async (sid: string) => {
    try {
      const scope = await getSessionScope(sid)
      window.clearTimeout(scopeSaveTimerRef.current) // 丢弃切换前未发出的保存
      // KB 三态恢复：[]=都不加载（显哨兵、标签清空）；null=默认全部（空选择器）
      const kbNone = Array.isArray(scope.kbProjects) && scope.kbProjects.length === 0
      setKbFilter(
        kbNone
          ? { projects: [KB_NONE], tags: [] }
          : { projects: scope.kbProjects ?? [], tags: scope.kbTags ?? [] },
      )
      // 工具三态恢复：单侧 [] → 该侧显哨兵；两侧皆 [] → 仍推导 enabled=false（迭代12 兼容）
      const dbNone = Array.isArray(scope.toolNames) && scope.toolNames.length === 0
      const mcpNone = Array.isArray(scope.mcpServers) && scope.mcpServers.length === 0
      const allOff = dbNone && mcpNone
      setToolScope(
        allOff
          ? { enabled: false, toolNames: [], mcpServers: [] }
          : {
              enabled: true,
              toolNames: dbNone ? [DB_TOOLS_NONE] : (scope.toolNames ?? undefined),
              mcpServers: mcpNone ? [MCP_NONE] : (scope.mcpServers ?? undefined),
            },
      )
    } catch {
      // 范围读取失败不打扰会话切换
    }
  }

  /** 新会话/删当前会话：范围回默认（全部 KB + 启用工具）。 */
  const resetScope = () => {
    window.clearTimeout(scopeSaveTimerRef.current)
    setKbFilter({ projects: [], tags: [] })
    setToolScope({ enabled: true })
  }

  const handleKbFilterChange = (v: KbFilterValue) => {
    setKbFilter(v)
    persistScopeDebounced(v, toolScope)
  }

  const handleToolScopeChange = (v: ToolScopeValue) => {
    setToolScope(v)
    persistScopeDebounced(kbFilter, v)
  }

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
        void restoreScope(sid) // 迭代12：回填会话级范围
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

  // 迭代13：轮次结束（streaming → 非 streaming）后静默对齐库 id 到直播消息，
  // 使刚完成的这轮也能立即使用删除/截断；失败静默（切回会话即有 id）。
  const prevStatusRef = useRef(chat.status)
  useEffect(() => {
    const prev = prevStatusRef.current
    prevStatusRef.current = chat.status
    const sid = chat.currentSessionId
    if (prev !== 'streaming' || chat.status === 'streaming' || !sid || !chat.remember) return
    void sessions
      .selectSession(sid)
      .then((res) => {
        if (res.kind === 'ok') chat.attachBackendIds(res.messages)
      })
      .catch(() => {})
    // sessions/chat 引用均为稳定 useCallback；仅关心状态跃迁与会话归属
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chat.status, chat.currentSessionId, chat.remember])

  /** 切换历史会话；流式中阻止（FR-15.3）。 */
  const handleSelect = async (id: string) => {
    if (chat.isStreaming) {
      message.warning(STREAMING_BLOCK_MESSAGE)
      return
    }
    const res = await sessions.selectSession(id)
    if (res.kind === 'ok') {
      chat.showHistory(id, res.messages)
      void restoreScope(id) // 迭代12：回填该会话持久化的 KB/工具范围
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
    resetScope()
    chat.startNew()
  }

  const handleDelete = async (id: string) => {
    try {
      await sessions.removeSession(id)
      // 删的是当前会话 → 清空主区
      if (id === chat.currentSessionId) {
        resetScope()
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

  // ---- 迭代13：消息级删除/截断 + 清空上下文 + 批量删会话 ----

  /** 静默重载当前会话历史（删除/截断/清空后权威态回灌，含分隔线）。 */
  const reloadHistory = async (sid: string) => {
    const res = await sessions.selectSession(sid)
    if (res.kind === 'ok') {
      chat.showHistory(sid, res.messages)
    } else if (res.kind === 'notfound') {
      chat.startNew()
    }
  }

  /** 删除单轮（FR-1）：成对删用户消息 + AI 回复，物理删除不可恢复。 */
  const handleDeleteTurn = async (m: ChatMessage) => {
    const sid = chat.currentSessionId
    if (!sid || m.backendId == null || chat.isStreaming) return
    try {
      await deleteTurn(sid, m.backendId)
      await reloadHistory(sid)
    } catch {
      message.error('删除失败，请重试')
    }
  }

  /** 截断重问（FR-2）：删该条及之后全部消息，内容回填输入框可编辑重发。 */
  const handleTruncate = async (m: ChatMessage) => {
    const sid = chat.currentSessionId
    if (!sid || m.backendId == null || chat.isStreaming) return
    try {
      await truncateMessages(sid, m.backendId)
      setDraft(m.content)
      await reloadHistory(sid)
    } catch {
      message.error('截断失败，请重试')
    }
  }

  /** 清空上下文但保留记录（FR-5）：标记点之后模型从零开始。 */
  const handleClearContext = () => {
    const sid = chat.currentSessionId
    if (!sid || chat.isStreaming) return
    Modal.confirm({
      title: '清空上下文？',
      content: '消息记录保留可见，但模型从下一轮起不再携带此前历史。',
      okText: '清空',
      cancelText: '取消',
      okButtonProps: { danger: true, 'data-testid': 'clear-context-confirm' } as never,
      onOk: async () => {
        try {
          await clearContext(sid)
          await reloadHistory(sid)
        } catch {
          message.error('清空失败，请重试')
        }
      },
    })
  }

  /** 主区删除当前会话（FR-4）：与侧边栏删除同语义同接口。 */
  const handleDeleteCurrentSession = () => {
    const sid = chat.currentSessionId
    if (!sid || chat.isStreaming) return
    Modal.confirm({
      title: '删除当前会话？',
      content: '将删除该会话及其全部消息，不可恢复。',
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true, 'data-testid': 'delete-current-confirm' } as never,
      onOk: async () => {
        try {
          await sessions.removeSession(sid)
          resetScope()
          chat.startNew()
        } catch {
          message.error('会话删除失败，请重试')
        }
      },
    })
  }

  /** 批量删除会话（FR-3）：逐 id 汇报；当前会话被删回空态；notFound 提示。 */
  const handleBatchDelete = async (ids: string[]) => {
    try {
      const result = await sessions.removeSessions(ids)
      if (result.notFound.length > 0) {
        message.warning(`${result.notFound.length} 个会话已不存在，列表已刷新`)
      }
      if (chat.currentSessionId && result.deleted.includes(chat.currentSessionId)) {
        resetScope()
        chat.startNew()
      }
    } catch {
      message.error('批量删除失败，请重试')
    }
  }

  const handleSend = (text: string) => {
    // 工具选择器隐藏（工具不可用）时不带范围键，请求形态与迭代11 逐字节一致
    void chat.send(text, kbFilter, toolOpts.available ? toolScope : undefined)
    setDraft('')
  }

  const handlePickExample = (prompt: string) => {
    if (chat.isStreaming) {
      message.warning(STREAMING_BLOCK_MESSAGE)
      return
    }
    void chat.send(prompt, kbFilter, toolOpts.available ? toolScope : undefined)
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
          onBatchDelete={handleBatchDelete}
          batchDisabled={chat.isStreaming}
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
          {/* 迭代13：危险入口收纳「⋯」（清空上下文 FR-5 / 删除当前会话 FR-4） */}
          <Dropdown
            menu={{
              items: [
                { key: 'clear-context', label: '清空上下文', 'data-testid': 'menu-clear-context' },
                { key: 'delete-session', label: '删除当前会话', danger: true, 'data-testid': 'menu-delete-session' },
              ],
              onClick: ({ key }) => {
                if (key === 'clear-context') handleClearContext()
                if (key === 'delete-session') handleDeleteCurrentSession()
              },
            }}
            disabled={!chat.currentSessionId || chat.isStreaming}
            trigger={['click']}
          >
            <Button size="small" data-testid="header-more-menu" aria-label="更多操作">
              ⋯
            </Button>
          </Dropdown>
        </>
      }
    >
      <div className="chat-main">
        {chat.messages.length === 0 ? (
          <div className="empty-state-wrap">
            <EmptyState onPick={handlePickExample} />
          </div>
        ) : (
          <MessageList
            messages={chat.messages}
            onDeleteTurn={chat.currentSessionId && !chat.isStreaming ? handleDeleteTurn : undefined}
            onTruncate={chat.currentSessionId && !chat.isStreaming ? handleTruncate : undefined}
          />
        )}
      </div>
      <InlineError error={chat.lastError} onClose={chat.dismissError} />
      {/* 界面美化：KB/工具筛选条收纳为一条圆角「范围工具条」（任一可用即渲染） */}
      {(kbDims.available || toolOpts.available) && (
        <div className="chat-scope-bar" data-testid="chat-scope-bar">
          {kbDims.available && (
            <KbFilterBar
              projects={kbDims.projects}
              tags={kbDims.tags}
              value={kbFilter}
              onChange={handleKbFilterChange}
              disabled={chat.isStreaming}
            />
          )}
          {toolOpts.available && (
            <ToolScopeBar
              dbTools={toolOpts.dbTools}
              mcpServers={toolOpts.mcpServers}
              value={toolScope}
              onChange={handleToolScopeChange}
              disabled={chat.isStreaming}
            />
          )}
        </div>
      )}
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
