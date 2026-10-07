import { httpProtocol } from '../constants/contracts'
import { streamEventNames, streamEventType } from '../constants/chat'
import { ApiRequestError, http, handleUnauthorized } from './http'
import { useAuthStore } from '../stores/auth'
import { SseParser } from './sse'
import type { StreamEvent } from '../types/chat'
import type { ErrorCode } from '../types/contracts'

/** 使用当前会话认证取消指定生成请求。 */
export async function cancelChat(requestId: string) {
  await http.post(`/chat/${encodeURIComponent(requestId)}/cancel`)
}

/** 发送提问并按 SSE 帧交付事件，区分结构化失败、终态与意外断流。 */
export async function streamChat(
  body: { conversationId: number; knowledgeBaseIds: number[]; message: string },
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
  onRequest: (id: string) => void,
) {
  const token = useAuthStore().token
  const response = await fetch(`${httpProtocol.apiBaseUrl}/chat/stream`, {
    method: httpProtocol.post,
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      [httpProtocol.authHeader]: token ?? '',
    },
    body: JSON.stringify(body),
  })
  const requestId = response.headers.get(httpProtocol.requestIdHeader)
  if (!response.ok) {
    const error = (await response.json()) as { code?: ErrorCode; message?: string; requestId?: string }
    if (response.status === httpProtocol.unauthorized && token) {
      handleUnauthorized(token)
    }
    throw new ApiRequestError(
      error.message ?? '问答请求失败',
      error.requestId ?? requestId,
      response.status,
      error.code ?? null,
    )
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('服务器未返回问答流')
  }
  if (requestId) {
    onRequest(requestId)
  }
  let terminal = false

  /** 校验事件白名单和结构，只交付首个终态之前的公开流事件。 */
  const parser = new SseParser(frame => {
    if (terminal || !streamEventNames.includes(frame.event)) {
      return
    }
    const data: unknown = JSON.parse(frame.data)
    if (!data || typeof data !== 'object') {
      throw new Error('无效 SSE 数据')
    }
    if (frame.event === streamEventType.done || frame.event === streamEventType.error) {
      terminal = true
    }
    onEvent({ type: frame.event, data } as StreamEvent)
  })
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (!terminal) {
      const chunk = await reader.read()
      if (chunk.done) {
        parser.push(decoder.decode())
        break
      }
      parser.push(decoder.decode(chunk.value, { stream: true }))
    }
    if (!terminal) {
      throw new Error('连接中断，已从历史恢复消息；请核对状态后手动重试。')
    }
  } finally {
    // 连接可能已关闭；清理失败不应覆盖原始业务错误。
    await reader.cancel().catch(() => {
    })
    reader.releaseLock()
  }
}
