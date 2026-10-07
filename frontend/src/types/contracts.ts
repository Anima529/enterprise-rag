export type ErrorCode =
  | 'COMMON_400_VALIDATION'
  | 'AUTH_401_UNAUTHENTICATED'
  | 'AUTH_401_INVALID_CREDENTIALS'
  | 'AUTH_403_FORBIDDEN'
  | 'KB_403_FORBIDDEN'
  | 'KB_404_NOT_FOUND'
  | 'KB_409_DISABLED'
  | 'DOC_404_NOT_FOUND'
  | 'DOC_409_DUPLICATE'
  | 'DOC_409_VERSION_CONFLICT'
  | 'DOC_413_TOO_LARGE'
  | 'DOC_415_UNSUPPORTED_FORMAT'
  | 'DOC_422_INVALID_CONTENT'
  | 'DOC_422_PARSE_FAILED'
  | 'RAG_403_EMPTY_SCOPE'
  | 'RAG_503_MODEL_UNAVAILABLE'
  | 'RAG_503_RETRIEVAL_UNAVAILABLE'
  | 'RAG_503_RERANK_UNAVAILABLE'
  | 'DOC_422_EMPTY_TEXT'
  | 'DOC_422_OCR_REQUIRED'
  | 'DOC_413_PARSE_LIMIT'
  | 'DOC_504_PARSE_TIMEOUT'
  | 'DOC_409_TASK_NOT_RETRYABLE'
  | 'DOC_409_SOURCE_UNAVAILABLE'
  | 'DOC_503_STAGE_UNAVAILABLE'
  | 'DOC_504_TASK_TIMEOUT'
  | 'DOC_500_INDEX_FAILED'
  | 'RAG_504_TIMEOUT'
  | 'RAG_429_RATE_LIMITED'
  | 'MODEL_422_DIMENSION_MISMATCH'
  | 'RAG_429_BUSY'
  | 'RAG_429_RATE_LIMIT'
  | 'RAG_503_GOVERNANCE_UNAVAILABLE'
  | 'EVAL_404_NOT_FOUND'
  | 'EVAL_409_SNAPSHOT_CHANGED'
  | 'EVAL_409_QUEUE_FULL'
  | 'CHAT_404_NOT_FOUND'
  | 'CHAT_409_BUSY'
  | 'USAGE_409_PRICE_EXISTS'
  | 'STORAGE_503_UNAVAILABLE'
  | 'COMMON_500_INTERNAL'
  | 'COMMON_404_NOT_FOUND'
  | 'COMMON_405_METHOD_NOT_ALLOWED'
  | 'COMMON_415_UNSUPPORTED_MEDIA_TYPE'

export interface ApiSuccess<T> {
  code: 0
  message: string
  data: T
}
export interface ApiError {
  code: ErrorCode
  message: string
  requestId: string
}
export interface PageResponse<T> {
  records: T[]
  page: number
  pageSize: number
  total: number
}
export type Permission = 'READ' | 'WRITE' | 'ADMIN'
export type SubjectType = 'USER' | 'DEPARTMENT' | 'ROLE'
export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'CHUNKING' | 'EMBEDDING' | 'INDEXING' | 'COMPLETED' | 'FAILED'
export type VerificationStatus = 'SUPPORTED' | 'PARTIALLY_SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN'
export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW'
export type RetrievalStage = 'REWRITING' | 'ROUTING' | 'RETRIEVING' | 'RERANKING' | 'GENERATING' | 'VERIFYING'

export interface Citation {
  citationId: string
  chunkId: number
  documentId: number
  documentVersion: number
  documentName: string
  page: number | null
  chapter: string | null
  section: string | null
  lineStart: number | null
  lineEnd: number | null
  snippet: string
  tables?: import('./chat').CitationTable[]
  preview?: import('./source-preview').SourcePreviewBlock[]
}

export type ChatStreamEvent =
  | { type: 'status'; data: { stage: RetrievalStage; message: string } }
  | { type: 'content'; data: { delta: string } }
  | { type: 'citation'; data: Citation }
  | {
      type: 'usage'
      data: { inputTokens: number | null; outputTokens: number | null; estimatedCost: number | null; currency: 'CNY' }
    }
  | { type: 'verification'; data: { status: VerificationStatus; confidenceLevel: ConfidenceLevel } }
  | {
      type: 'done'
      data: { messageId: number; requestId: string; traceId: string; finishReason: 'COMPLETED' | 'CANCELLED' }
    }
  | { type: 'error'; data: ApiError }

export interface LoginRequest {
  username: string
  password: string
}
export interface CurrentUser {
  id: number
  username: string
  departmentId: number
  roles: string[]
}
export interface LoginResponse {
  token: string
  user: CurrentUser
}
export interface UploadResponse {
  documentId: number
  documentVersion: number
  status: DocumentStatus
}
export interface ChatRequest {
  conversationId: number
  knowledgeBaseIds: number[]
  message: string
}
