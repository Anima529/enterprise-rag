import { pageSizes } from '../config/client'
import { http } from './http'
import type { ApiSuccess, PageResponse } from '../types/contracts'
import type { ChatMessage, Conversation, CitationSource } from '../types/chat'
import type { DownloadResponse } from '../types/document'

/** 按页读取当前用户的会话列表。 */
export async function listConversations(page = 1) {
  const response = await http.get<ApiSuccess<PageResponse<Conversation>>>('/conversations', {
    params: { page, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 按标题创建当前用户的新会话。 */
export async function createConversation(title: string) {
  const response = await http.post<ApiSuccess<Conversation>>('/conversations', { title })
  return response.data.data
}

/** 更新会话标题并返回最新会话信息。 */
export async function renameConversation(id: number, title: string) {
  const response = await http.put<ApiSuccess<Conversation>>(`/conversations/${id}`, { title })
  return response.data.data
}

/** 请求删除当前用户所属会话。 */
export async function deleteConversation(id: number) {
  await http.delete(`/conversations/${id}`)
}

/** 查询指定会话的最新元数据。 */
export async function getConversation(id: number) {
  const response = await http.get<ApiSuccess<Conversation>>(`/conversations/${id}`)
  return response.data.data
}

/** 按页读取会话历史消息及引用快照。 */
export async function getMessages(id: number, page = 1) {
  const response = await http.get<ApiSuccess<PageResponse<ChatMessage>>>(`/conversations/${id}/messages`, {
    params: { page, pageSize: pageSizes.messages },
  })
  return response.data.data
}

/** 原文定位使用消息引用编号，并由后端重新校验会话和文档权限。 */
export async function getCitationSource(conversationId: number, messageId: number, citationId: string) {
  const path = `/conversations/${conversationId}/messages/${messageId}/citations/${encodeURIComponent(citationId)}/source`
  const response = await http.get<ApiSuccess<CitationSource>>(path)
  return response.data.data
}

/** 每次打开 PDF 都重新请求引用版本的短期 inline 地址。 */
export async function getCitationPdf(conversationId: number, messageId: number, citationId: string) {
  const path = `/conversations/${conversationId}/messages/${messageId}/citations/${encodeURIComponent(citationId)}/pdf`
  const response = await http.get<ApiSuccess<DownloadResponse>>(path)
  return response.data.data
}
