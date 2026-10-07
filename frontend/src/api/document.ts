import { PERCENT_SCALE } from '../constants/units'
import { clientConfig, pageSizes } from '../config/client'
import type { ApiSuccess, PageResponse, UploadResponse } from '../types/contracts'
import type {
  ChunkPreview,
  ChunkType,
  DocumentRecord,
  DocumentTask,
  DownloadResponse,
  EmbeddingPreview,
  IndexStep,
  ParsePreview,
  UploadPolicy,
} from '../types/document'
import { http } from './http'

/** 按页读取知识库中的授权文档。 */
export async function listDocuments(kbId: number, page = 1) {
  const response = await http.get<ApiSuccess<PageResponse<DocumentRecord>>>(`/knowledge-bases/${kbId}/documents`, {
    params: { page, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 读取服务器允许的上传格式和资源限制。 */
export async function getUploadPolicy(kbId: number) {
  const response = await http.get<ApiSuccess<UploadPolicy>>(`/knowledge-bases/${kbId}/documents/upload-policy`)
  return response.data.data
}

/** 以 multipart 上传新文档并报告传输进度，索引状态由后续查询获取。 */
export async function uploadDocument(kbId: number, file: File, onProgress: (percent: number) => void) {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiSuccess<UploadResponse>>(`/knowledge-bases/${kbId}/documents`, form, {
    timeout: clientConfig.uploadTimeoutMs,

    /** 仅在总大小已知时换算上传百分比并通知调用方。 */
    onUploadProgress: event => {
      if (event.total) {
        onProgress(Math.round((event.loaded * PERCENT_SCALE) / event.total))
      }
    },
  })
  return response.data.data
}

/** 查询文档当前版本和状态。 */
export async function getDocument(id: number) {
  const response = await http.get<ApiSuccess<DocumentRecord>>(`/documents/${id}`)
  return response.data.data
}

/** 按页读取文档索引任务及重试状态。 */
export async function getDocumentTasks(id: number) {
  const response = await http.get<ApiSuccess<PageResponse<DocumentTask>>>(`/documents/${id}/tasks`, {
    params: { page: 1, pageSize: pageSizes.default },
  })
  return response.data.data
}

/** 按版本和分页读取解析预览，缺省版本由服务器决定。 */
export async function getParsedBlocks(id: number, page = 1, version?: number) {
  const response = await http.get<ApiSuccess<ParsePreview>>(`/documents/${id}/parsed-blocks`, {
    params: { page, pageSize: pageSizes.default, version },
  })
  return response.data.data
}

/** 按版本、类型和分页读取带来源的 Chunk 预览。 */
export async function getDocumentChunks(id: number, page = 1, version?: number, chunkType?: ChunkType) {
  const response = await http.get<ApiSuccess<ChunkPreview>>(`/documents/${id}/chunks`, {
    params: { page, pageSize: pageSizes.default, version, chunkType },
  })
  return response.data.data
}

/** 请求指定或当前版本的限时原文件下载地址。 */
export async function getDocumentDownload(id: number, version?: number) {
  const response = await http.get<ApiSuccess<DownloadResponse>>(`/documents/${id}/download`, { params: { version } })
  return response.data.data
}

/** 查询版本的 Embedding 汇总，不暴露原始向量。 */
export async function getDocumentEmbedding(id: number, version?: number) {
  const response = await http.get<ApiSuccess<EmbeddingPreview>>(`/documents/${id}/embedding`, { params: { version } })
  return response.data.data
}

/** 请求软删除文档并交由服务器安排清理。 */
export async function deleteDocument(id: number) {
  await http.delete(`/documents/${id}`)
}

/** 携带预期当前版本上传新原文件，交由服务器检测版本冲突。 */
export async function uploadDocumentVersion(id: number, expectedCurrentVersion: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  form.append('expectedCurrentVersion', String(expectedCurrentVersion))
  const response = await http.post<ApiSuccess<UploadResponse>>(`/documents/${id}/versions`, form, {
    timeout: clientConfig.uploadTimeoutMs,
  })
  return response.data.data
}

/** 携带预期版本申请重新索引，保留当前可检索版本。 */
export async function reindexDocument(id: number, expectedCurrentVersion: number) {
  const response = await http.post<ApiSuccess<UploadResponse>>(`/documents/${id}/reindex`, { expectedCurrentVersion })
  return response.data.data
}

/** 申请重新排队指定失败索引任务。 */
export async function retryDocumentTask(id: number, taskId: number) {
  await http.post(`/documents/${id}/tasks/${taskId}/retry`)
}

/** 分页查询任务的阶段执行记录。 */
export async function getDocumentTaskSteps(id: number, taskId: number, page = 1) {
  const response = await http.get<ApiSuccess<PageResponse<IndexStep>>>(`/documents/${id}/tasks/${taskId}/steps`, {
    params: { page, pageSize: pageSizes.indexSteps },
  })
  return response.data.data
}
