/** 消息与 SSE 稳定协议值；类型声明和后端字符串契约保持一致。 */
export const messageRole = { user: 'USER', assistant: 'ASSISTANT' } as const
export const messageStatus = {
  generating: 'GENERATING',
  completed: 'COMPLETED',
  failed: 'FAILED',
  cancelled: 'CANCELLED',
} as const
export const verificationStatus = {
  supported: 'SUPPORTED',
  partiallySupported: 'PARTIALLY_SUPPORTED',
  unsupported: 'UNSUPPORTED',
  unknown: 'UNKNOWN',
} as const
export const confidenceLevel = { high: 'HIGH', medium: 'MEDIUM', low: 'LOW' } as const
export const streamEventType = {
  status: 'status',
  content: 'content',
  citation: 'citation',
  usage: 'usage',
  verification: 'verification',
  done: 'done',
  error: 'error',
} as const
export const streamEventNames: readonly string[] = Object.values(streamEventType)
