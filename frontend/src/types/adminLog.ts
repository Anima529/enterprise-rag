export type RequestLogStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type LogKind = 'audit' | 'requests'

export interface LogQuery {
  from: string
  to: string
  userId?: number
  requestId?: string
  action?: string
  status?: RequestLogStatus
}

export interface AuditLog {
  id: number
  userId: number | null
  username: string | null
  action: string
  resourceId: number | null
  requestId: string | null
  traceId: string | null
  createdAt: string
}

export interface RequestLog {
  requestId: string
  traceId: string
  userId: number
  username: string | null
  departmentName: string | null
  messageId: number | null
  route: string
  reasonCode: string
  status: RequestLogStatus
  latencyMs: number
  firstTokenMs: number | null
  cacheHit: boolean
  errorCode: string | null
  createdAt: string
  updatedAt: string
}
