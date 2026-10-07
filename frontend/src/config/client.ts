/** 客户端资源预算默认值集中定义，可通过 Vite 环境变量覆盖时限和轮询间隔。 */
export const clientConfig = Object.freeze({
  apiTimeoutMs: positiveInteger(import.meta.env.VITE_API_TIMEOUT_MS, 12000, 'VITE_API_TIMEOUT_MS'),
  uploadTimeoutMs: positiveInteger(import.meta.env.VITE_UPLOAD_TIMEOUT_MS, 90000, 'VITE_UPLOAD_TIMEOUT_MS'),
  retrievalTimeoutMs: positiveInteger(import.meta.env.VITE_RETRIEVAL_TIMEOUT_MS, 30000, 'VITE_RETRIEVAL_TIMEOUT_MS'),
  documentPollIntervalMs: positiveInteger(
    import.meta.env.VITE_DOCUMENT_POLL_INTERVAL_MS,
    5000,
    'VITE_DOCUMENT_POLL_INTERVAL_MS',
  ),
  chatPollIntervalMs: positiveInteger(import.meta.env.VITE_CHAT_POLL_INTERVAL_MS, 3000, 'VITE_CHAT_POLL_INTERVAL_MS'),
  maxSseEventCharacters: 1048576,
  conversationTitlePreviewCharacters: 50,
})

/** 分页尺寸与 API 现有默认值保持一致，页面禁用条件和请求共用同一来源。 */
export const pageSizes = Object.freeze({ default: 20, messages: 100, indexSteps: 100 })

/** 空配置使用默认值，非法时限立即报告，避免把 NaN 或零值传入请求与计时器。 */
export function positiveInteger(value: string | undefined, fallback: number, name: string): number {
  if (value === undefined || value === '') {
    return fallback
  }
  const parsed = Number(value)
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} 必须为正整数`)
  }
  return parsed
}
