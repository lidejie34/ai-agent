// 管理令牌仓（迭代 H）：内存优先 + localStorage 独立键 admin.token（与 chat.* 隔离，AC-50）。
// localStorage 不可用时静默降级为仅内存（D9/AC-14）；token 绝不进 URL/console/文案（AC-13）。

const TOKEN_KEY = 'admin.token'

let memoryToken: string | null = null // 内存态兜底（localStorage 不可用 / 登录验证期暂存）
const listeners = new Set<() => void>()

function safeGet(k: string): string | null {
  try {
    return localStorage.getItem(k)
  } catch {
    return null
  }
}

function safeSet(k: string, v: string): void {
  try {
    localStorage.setItem(k, v)
  } catch {
    /* 存储不可用：静默降级，仅内存有效 */
  }
}

function safeRemove(k: string): void {
  try {
    localStorage.removeItem(k)
  } catch {
    /* ignore */
  }
}

/** 当前 token：内存优先；刷新后内存丢失则读 localStorage。 */
export function getAdminToken(): string | null {
  return memoryToken ?? safeGet(TOKEN_KEY)
}

/** 登录成功后调用：内存 + localStorage（写入失败静默降级）。 */
export function persistAdminToken(token: string): void {
  memoryToken = token
  safeSet(TOKEN_KEY, token)
}

/** 登录验证期间临时持有 token（只存内存，验证失败不落盘，AC-8）。 */
export function stageToken(token: string): void {
  memoryToken = token
}

/** 退出/401/登录失败：清内存与盘。 */
export function clearAdminToken(): void {
  memoryToken = null
  safeRemove(TOKEN_KEY)
}

/** 401 广播：admin client 捕获 ADMIN_UNAUTHORIZED 时调用；去重由订阅方把关（AC-47）。 */
export function notifyUnauthorized(): void {
  listeners.forEach((fn) => fn())
}

export function onUnauthorized(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
