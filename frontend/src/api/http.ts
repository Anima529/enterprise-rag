import { httpProtocol } from '../constants/contracts'
import { clientConfig } from '../config/client'
import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import type { ApiError, ErrorCode } from '../types/contracts'

export class ApiRequestError extends Error {
  /** 保留结构化错误码、HTTP 状态与 requestId，供业务判断和问题排查；界面提示只展示 message。 */
  constructor(
    message: string,
    public readonly requestId: string | null,
    public readonly status: number | null,
    public readonly code: ErrorCode | null = null,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

export const http = axios.create({ baseURL: httpProtocol.apiBaseUrl, timeout: clientConfig.apiTimeoutMs })
let unauthorizedHandler: (() => void) | null = null

/** 登记当前会话失效后的路由处理函数。 */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

/** 只清理请求所用 Token 仍对应的会话，避免旧响应覆盖新登录。 */
export function handleUnauthorized(sentToken: string) {
  const auth = useAuthStore()
  if (sentToken === auth.token) {
    auth.clear()
    unauthorizedHandler?.()
  }
}

/** 为请求附加当前 Token，保持认证凭据只通过请求头传递。 */
http.interceptors.request.use(config => {
  const token = useAuthStore().token
  if (token) {
    config.headers.set(httpProtocol.authHeader, token)
  }
  return config
})

/** 保留成功响应；失败时统一错误结构，仅清理仍对应当前会话的失效 Token。 */
http.interceptors.response.use(
  response => response,
  (error: unknown) => {
    if (!axios.isAxiosError<ApiError>(error)) {
      return Promise.reject(error)
    }
    const status = error.response?.status ?? null
    const auth = useAuthStore()
    const sentToken = error.config?.headers.get(httpProtocol.authHeader)
    if (
      status === httpProtocol.unauthorized &&
      error.config?.url !== '/auth/login' &&
      sentToken &&
      sentToken === auth.token
    ) {
      auth.clear()
      unauthorizedHandler?.()
    }
    const body = error.response?.data
    const requestId = body?.requestId ?? error.response?.headers[httpProtocol.requestIdHeader] ?? null
    const message = typeof body?.message === 'string' ? body.message : '无法连接服务，请检查后端和开发代理。'
    return Promise.reject(
      new ApiRequestError(message, typeof requestId === 'string' ? requestId : null, status, body?.code ?? null),
    )
  },
)
