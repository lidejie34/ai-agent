import type { ApiError, SseFrame } from '../types'

// SSE 分帧纯函数（无 IO，可单测）。以 \n\n 分事件块（兼容 \r\n\r\n），
// 解析 event:/data: 行、注释帧（:开头）、多 data 行按 \n 拼接；
// event:done 的 data 是字面量 [DONE]，不 JSON.parse（AC-20/FR-11.2）。

/**
 * 解析一个完整事件块（不含分隔空行）为一帧；注释块 → comment；无法识别 → null。
 */
export function parseSseBlock(block: string): SseFrame | null {
  const lines = block.split(/\r?\n/)
  let event = 'message'
  const dataLines: string[] = []
  let isComment = false
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '')
    if (line.startsWith(':')) {
      isComment = true // :keepalive 等注释帧
      continue
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (line.startsWith('data:')) {
      let d = line.slice(5)
      if (d.startsWith(' ')) d = d.slice(1) // SSE 规范：去掉单个前导空格
      dataLines.push(d)
    }
  }
  if (isComment && dataLines.length === 0) {
    return { kind: 'comment' }
  }
  const data = dataLines.join('\n') // 多 data 行按 \n 拼接
  switch (event) {
    case 'session':
      return { kind: 'session', sessionId: (JSON.parse(data) as { sessionId: string }).sessionId }
    case 'message':
      return { kind: 'chunk', content: (JSON.parse(data) as { content: string }).content }
    case 'done':
      return { kind: 'done' } // data 为字面量 [DONE]，不解析
    case 'error':
      return { kind: 'error', error: JSON.parse(data) as ApiError }
    default:
      return null
  }
}

/**
 * 从累计 buffer 中切出完整事件块（\n\n，兼容 \r\n\r\n），返回已解析帧与未完成尾巴。
 */
export function drainFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const blocks = buffer.split(/\r?\n\r?\n/)
  const rest = blocks.pop() ?? '' // 最后一段可能是不完整事件
  const frames: SseFrame[] = []
  for (const b of blocks) {
    if (!b.trim()) continue
    const f = parseSseBlock(b)
    if (f) frames.push(f)
  }
  return { frames, rest }
}
