import { pageSizes } from '../config/client'
import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type { Department, Page, User } from '../types/auth'

/** 按页读取管理员可见的用户目录。 */
export async function listUsers(page = 1): Promise<Page<User>> {
  const response = await http.get<ApiSuccess<Page<User>>>('/admin/users', {
    params: { page, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 按页读取管理员可见的部门目录。 */
export async function listDepartments(page = 1): Promise<Page<Department>> {
  const response = await http.get<ApiSuccess<Page<Department>>>('/admin/departments', {
    params: { page, pageSize: pageSizes.default },
  })
  return response.data.data
}
