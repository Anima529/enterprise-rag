import { http } from './http'
import { pageSizes } from '../config/client'
import type { ApiSuccess } from '../types/contracts'
import type { Page } from '../types/auth'
import type { AuditLog, LogQuery, RequestLog } from '../types/adminLog'

/** 读取管理员审计分页，认证和错误处理沿用统一 HTTP 客户端。 */
export async function listAuditLogs(query: LogQuery, page: number): Promise<Page<AuditLog>> {
  const response = await http.get<ApiSuccess<Page<AuditLog>>>('/admin/logs/audit', {
    params: { ...query, page, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 读取脱敏请求追踪，接口不返回聊天正文和检索快照。 */
export async function listRequestLogs(query: LogQuery, page: number): Promise<Page<RequestLog>> {
  const response = await http.get<ApiSuccess<Page<RequestLog>>>('/admin/logs/requests', {
    params: { ...query, page, pageSize: pageSizes.default },
  })
  return response.data.data
}
