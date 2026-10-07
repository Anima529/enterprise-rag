import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type { LoginRequest, LoginResponse, User } from '../types/auth'

/** 提交登录凭据并返回 Token 与公开用户信息。 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await http.post<ApiSuccess<LoginResponse>>('/auth/login', request)
  return response.data.data
}

/** 读取当前会话的最新有效用户信息。 */
export async function me(): Promise<User> {
  const response = await http.get<ApiSuccess<User>>('/auth/me')
  return response.data.data
}

/** 请求服务器撤销当前登录会话。 */
export async function logout(): Promise<void> {
  await http.post('/auth/logout')
}
