import { pageSizes } from '../config/client'
import { http } from './http'
import type { ApiSuccess, PageResponse } from '../types/contracts'
import type { AclEntry, CreateKnowledgeBase, KnowledgeBase, UpdateKnowledgeBase } from '../types/knowledgeBase'

/** 按显式管理标志选择普通或管理员知识库入口。 */
function path(management: boolean) {
  return management ? '/admin/knowledge-bases' : '/knowledge-bases'
}

/** 分页查询当前入口可见的知识库。 */
export async function listKnowledgeBases(page = 1, management = false): Promise<PageResponse<KnowledgeBase>> {
  const response = await http.get<ApiSuccess<PageResponse<KnowledgeBase>>>(path(management), {
    params: { page, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 通过指定入口读取知识库详情。 */
export async function getKnowledgeBase(id: number, management = false): Promise<KnowledgeBase> {
  const response = await http.get<ApiSuccess<KnowledgeBase>>(`${path(management)}/${id}`)
  return response.data.data
}

/** 向管理员入口提交新知识库属性。 */
export async function createKnowledgeBase(body: CreateKnowledgeBase): Promise<KnowledgeBase> {
  const response = await http.post<ApiSuccess<KnowledgeBase>>('/knowledge-bases', body)
  return response.data.data
}

/** 通过指定入口更新知识库可编辑属性。 */
export async function updateKnowledgeBase(
  id: number,
  body: UpdateKnowledgeBase,
  management = false,
): Promise<KnowledgeBase> {
  const response = await http.put<ApiSuccess<KnowledgeBase>>(`${path(management)}/${id}`, body)
  return response.data.data
}

/** 通过指定入口申请删除知识库。 */
export async function deleteKnowledgeBase(id: number, management = false): Promise<void> {
  await http.delete(`${path(management)}/${id}`)
}

/** 通过指定入口读取知识库完整授权集合。 */
export async function getAcl(id: number, management = false): Promise<AclEntry[]> {
  const response = await http.get<ApiSuccess<AclEntry[]>>(`${path(management)}/${id}/acl`)
  return response.data.data
}

/** 提交完整 ACL 替换集合，空集合表示撤销全部显式授权。 */
export async function replaceAcl(id: number, entries: AclEntry[], management = false): Promise<AclEntry[]> {
  const response = await http.put<ApiSuccess<AclEntry[]>>(`${path(management)}/${id}/acl`, { entries })
  return response.data.data
}
