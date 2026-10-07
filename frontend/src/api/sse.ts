import { clientConfig } from '../config/client'
export interface SseFrame {
  id: string
  event: string
  data: string
}

/** 按行增量解析；网络分片可能拆开 UTF-8 字符或 SSE 帧，不能视为完整事件。 */
export class SseParser {
  private line = ''
  private previousWasCarriageReturn = false
  private dataLines: string[] = []
  private dataSize = 0
  private event = ''
  private id = ''
  private seenEventIds = new Set<string>()

  /** 登记完整 SSE 帧的交付回调。 */
  constructor(private deliver: (frame: SseFrame) => void) {
  }

  /** 增量接收文本，兼容跨分片换行并限制单事件大小。 */
  push(text: string) {
    for (const char of text) {
      if (char === '\r') {
        this.consume()
        this.previousWasCarriageReturn = true
      } else if (char === '\n') {
        if (!this.previousWasCarriageReturn) {
          this.consume()
        }
        this.previousWasCarriageReturn = false
      } else {
        this.previousWasCarriageReturn = false
        this.line += char
      }
      if (this.line.length + this.dataSize > clientConfig.maxSseEventCharacters) {
        throw new Error('SSE event exceeds size limit')
      }
    }
  }

  /** 消费完整行，忽略心跳并在空行处交付未重复的 SSE 事件。 */
  private consume() {
    const line = this.line
    this.line = ''
    if (!line) {
      if (this.dataLines.length && (!this.id || !this.seenEventIds.has(this.id))) {
        if (this.id) {
          this.seenEventIds.add(this.id)
        }
        this.deliver({ id: this.id, event: this.event || 'message', data: this.dataLines.join('\n') })
      }
      this.dataLines = []
      this.dataSize = 0
      this.event = ''
      this.id = ''
      return
    }
    if (line.startsWith(':')) {
      return
    }
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'data') {
      this.dataLines.push(value)
      this.dataSize += value.length
    } else if (field === 'event') {
      this.event = value
    } else if (field === 'id' && !value.includes('\0')) {
      this.id = value
    }
  }
}
