import { clientConfig } from '../config/client'
import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type { Complexity, RetrievalDebug } from '../types/retrieval'
import type { AdaptiveTrace } from '../types/retrieval'

/** 查看本人聊天请求的自动路线追踪，后端重新校验原知识库范围。 */
export async function getRetrievalTrace(requestId: string): Promise<AdaptiveTrace> {
  const response = await http.get<ApiSuccess<AdaptiveTrace>>(`/admin/retrieval/traces/${encodeURIComponent(requestId)}`)
  return response.data.data
}

/** 管理员在明确知识库范围内诊断召回与精排，返回实际入模证据。 */
export async function debugRetrieval(query: string, knowledgeBaseIds: number[], complexity: Complexity) {
  const response = await http.post<ApiSuccess<RetrievalDebug>>(
    '/admin/retrieval/debug',
    {
      query,
      knowledgeBaseIds,
      complexity,
    },
    { timeout: clientConfig.retrievalTimeoutMs },
  )
  return response.data.data
}
