import { describe, it, expect } from 'vitest'
import { parseSseBlock, drainFrames } from './sseFrames'

describe('parseSseBlock', () => {
  it('event:session 解析出 sessionId', () => {
    const f = parseSseBlock('event:session\ndata:{"sessionId":"abc-123"}')
    expect(f).toEqual({ kind: 'session', sessionId: 'abc-123' })
  })

  it('event:message 解析出 content（打字机片段）', () => {
    const f = parseSseBlock('event:message\ndata:{"content":"你好"}')
    expect(f).toEqual({ kind: 'chunk', content: '你好' })
  })

  it('event:done 的 [DONE] 不当 JSON 解析', () => {
    const f = parseSseBlock('event:done\ndata:[DONE]')
    expect(f).toEqual({ kind: 'done' })
  })

  it('event:error 解析出 ApiError', () => {
    const err = { code: 'MEMORY_UNAVAILABLE', message: '会话服务暂不可用', timestamp: '2026-09-03 10:00:00' }
    const f = parseSseBlock(`event:error\ndata:${JSON.stringify(err)}`)
    expect(f).toEqual({ kind: 'error', error: err })
  })

  it(':keepalive 注释块 → comment 帧', () => {
    expect(parseSseBlock(':keepalive')).toEqual({ kind: 'comment' })
  })

  it('缺省 event 名按 message 处理', () => {
    const f = parseSseBlock('data:{"content":"x"}')
    expect(f).toEqual({ kind: 'chunk', content: 'x' })
  })

  it('多余的空 data 行拼接后不影响 JSON 解析（多 data 行按换行拼接）', () => {
    // dataLines = ['{"content":"a"}', '']，join 后尾部换行被 JSON.parse 容忍
    const f = parseSseBlock('event:message\ndata:{"content":"a"}\ndata:')
    expect(f).toEqual({ kind: 'chunk', content: 'a' })
  })

  it('data: 后单个前导空格被去除', () => {
    const f = parseSseBlock('event:message\ndata: {"content":"x"}')
    expect(f).toEqual({ kind: 'chunk', content: 'x' })
  })

  it('CRLF 行尾兼容', () => {
    const f = parseSseBlock('event:message\r\ndata:{"content":"y"}\r\n')
    expect(f).toEqual({ kind: 'chunk', content: 'y' })
  })
})

describe('drainFrames', () => {
  it('按空行切出多帧，不完整尾巴保留', () => {
    const buf =
      'event:session\ndata:{"sessionId":"s1"}\n\n' +
      'event:message\ndata:{"content":"嗨"}\n\n' +
      'event:message\nda'
    const { frames, rest } = drainFrames(buf)
    expect(frames).toEqual([
      { kind: 'session', sessionId: 's1' },
      { kind: 'chunk', content: '嗨' },
    ])
    expect(rest).toBe('event:message\nda')
  })

  it('CRLFCRLF 分隔兼容', () => {
    const buf = ':keepalive\r\n\r\nevent:done\r\ndata:[DONE]\r\n\r\n'
    const { frames, rest } = drainFrames(buf)
    expect(frames).toEqual([{ kind: 'comment' }, { kind: 'done' }])
    expect(rest).toBe('')
  })

  it('注释帧穿插在数据流中被识别', () => {
    const buf =
      'event:message\ndata:{"content":"a"}\n\n' +
      ':keepalive\n\n' +
      'event:message\ndata:{"content":"b"}\n\n'
    const { frames } = drainFrames(buf)
    expect(frames.map((f) => f.kind)).toEqual(['chunk', 'comment', 'chunk'])
  })

  it('空 buffer 返回空帧与空尾巴', () => {
    expect(drainFrames('')).toEqual({ frames: [], rest: '' })
  })
})
