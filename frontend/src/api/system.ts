import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type { SystemStatus } from '../types/system'

/** 查询后端及基础设施状态摘要。 */
export async function getSystemStatus() {
  const response = await http.get<ApiSuccess<SystemStatus>>('/system/status', { params: { locale: 'zh-CN' } })
  if (response.data.code !== 0) {
    throw new Error('服务返回了不兼容的响应。')
  }
  return {
    status: response.data.data,
    requestId: String(response.headers['x-request-id'] ?? ''),
    traceId: String(response.headers['x-trace-id'] ?? ''),
  }
}
