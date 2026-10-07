import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type { UsageDashboard, UsagePrice, PriceRequest } from '../types/usage'

/** 按页面身份请求统计，普通入口的用户范围由后端固定。 */
export async function getUsage(from: string, to: string, management: boolean): Promise<UsageDashboard> {
  const path = management ? '/admin/usage/dashboard' : '/usage/dashboard'
  const response = await http.get<ApiSuccess<UsageDashboard>>(path, { params: { from, to } })
  return response.data.data
}

/** 管理员从数据库重建实时快照，返回可见的 Redis 状态。 */
export async function rebuildUsage(): Promise<{ redisStatus: string }> {
  const response = await http.post<ApiSuccess<{ redisStatus: string }>>('/admin/usage/rebuild')
  return response.data.data
}

/** 加载不可变价格版本列表，用于核对估算来源。 */
export async function listPrices(): Promise<UsagePrice[]> {
  const response = await http.get<ApiSuccess<UsagePrice[]>>('/admin/usage/prices')
  return response.data.data
}

/** 新增管理员确认的价格版本，仅应用到后续调用。 */
export async function createPrice(price: PriceRequest): Promise<void> {
  await http.post('/admin/usage/prices', price)
}
