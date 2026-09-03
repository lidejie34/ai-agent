// 本地持久化（FR-12.x）：仅存非敏感 UI 状态（当前会话 ID、输入草稿），
// 绝不存放任何密钥/模型响应。localStorage 不可用（隐私模式等）时静默降级。

export const SESSION_ID_KEY = 'chat.currentSessionId'
export const DRAFT_KEY_PREFIX = 'chat.draft:'

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* 存储不可用：静默 */
  }
}

function safeRemove(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}

/** 读取刷新前选中的会话 ID（仅在记忆模式下写入/恢复）。 */
export function readStoredSessionId(): string | null {
  return safeGet(SESSION_ID_KEY)
}

/** 写入/清除当前会话 ID；id 为 null 时移除键。 */
export function writeStoredSessionId(id: string | null): void {
  if (id) safeSet(SESSION_ID_KEY, id)
  else safeRemove(SESSION_ID_KEY)
}

export function draftKey(scope: string): string {
  return `${DRAFT_KEY_PREFIX}${scope}`
}

export function readDraft(scope: string): string {
  return safeGet(draftKey(scope)) ?? ''
}

export function writeDraft(scope: string, value: string): void {
  if (value) safeSet(draftKey(scope), value)
  else safeRemove(draftKey(scope))
}
