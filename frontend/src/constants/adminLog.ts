import type { RequestLogStatus } from '../types/adminLog'

/** 请求日志状态与数据库终态保持一致，页面提供对应中文提示。 */
export const requestLogStatuses: { value: RequestLogStatus; label: string }[] = [
  { value: 'RUNNING', label: '运行中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
]

export const ADMIN_LOG_TIME_ZONE = 'Asia/Shanghai'
export const ADMIN_LOG_DEFAULT_DAYS = 7
export const MAX_SAFE_USER_ID = Number.MAX_SAFE_INTEGER
