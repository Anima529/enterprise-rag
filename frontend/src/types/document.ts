import type { DocumentStatus } from './contracts'

export type DocumentFormat = 'PDF' | 'DOC' | 'DOCX' | 'XLSX' | 'TXT' | 'MD'
export interface DocumentRecord {
  id: number
  knowledgeBaseId: number
  currentVersion: number
  name: string
  sourceFormat: DocumentFormat
  mimeType: string
  sizeBytes: number
  sha256: string
  status: DocumentStatus
  createdBy: number
  createdByUsername: string
  createdAt: string
  updatedAt: string
  errorCode: string | null
  errorMessage: string | null
}
export interface DocumentTask {
  id: number
  documentId: number
  documentVersion: number
  taskType: 'INDEX' | 'REINDEX'
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  retryCount: number
  errorCode: string | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
  stage: DocumentStatus
  attemptCount: number
  autoRetryCount: number
  nextAttemptAt: string
  leaseExpiresAt: string | null
}
export interface UploadPolicy {
  extensions: string[]
  maxBytes: number
  maxTextCharacters: number
}
export interface DownloadResponse {
  url: string
  expiresAt: string
}
export const documentStatus: Record<DocumentStatus, { label: string; tone: string }> = {
  UPLOADED: { label: '已上传，待处理', tone: 'pending' },
  PARSING: { label: '解析中', tone: 'pending' },
  CHUNKING: { label: '分块中', tone: 'pending' },
  EMBEDDING: { label: '向量化中', tone: 'pending' },
  INDEXING: { label: '索引中', tone: 'pending' },
  COMPLETED: { label: '索引完成', tone: 'up' },
  FAILED: { label: '处理失败', tone: 'down' },
}
export const taskStatus: Record<DocumentTask['status'], string> = {
  PENDING: '待处理',
  RUNNING: '处理中',
  COMPLETED: '完成',
  FAILED: '失败',
}

export interface IndexStep {
  id: number
  taskId: number
  attempt: number
  stage: DocumentStatus
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  startedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorMessage: string | null
}
export interface ParsedBlock {
  preview?: import('./source-preview').SourcePreviewBlock[]
  blockIndex: number
  text: string
  page: number | null
  chapter: string | null
  section: string | null
  metadata: {
    kind: string
    lineStart?: number
    lineEnd?: number
    headingLevel?: number
    headingPath?: string[]
    sheetName?: string
    header?: Record<string, string>
    headerRow?: number
    rowStart?: number
    rowEnd?: number
    paragraphIndex?: number
    language?: string
    closed?: boolean
    formulaPolicy?: string
    tableIndex?: number
    tableCells?: Record<string, string>
    tableHeader?: boolean
    tableRowComplete?: boolean
  }
}
export interface ParsePreview {
  documentId: number
  documentVersion: number
  format: DocumentFormat
  available: boolean
  parserVersion: string | null
  parsedAt: string | null
  characterCount: number
  warnings: string[]
  records: ParsedBlock[]
  total: number
  page: number
  pageSize: number
}
export type ChunkType = 'PARENT' | 'CHILD'
export interface ChunkSource {
  blockIndex: number
  charStart: number
  charEnd: number
  page: number | null
  chapter: string | null
  section: string | null
  metadata: ParsedBlock['metadata']
}
export interface DocumentChunk {
  preview?: import('./source-preview').SourcePreviewBlock[]
  id: number
  documentId: number
  documentVersion: number
  knowledgeBaseId: number
  departmentId: number
  parentChunkId: number | null
  chunkType: ChunkType
  chunkIndex: number
  content: string
  tokenCount: number
  pageNumber: number | null
  chapter: string | null
  section: string | null
  metadata: { sources: ChunkSource[] }
}
export interface ChunkPreview {
  documentId: number
  documentVersion: number
  available: boolean
  strategyVersion: string | null
  tokenizer: string | null
  chunkedAt: string | null
  childTokens: number | null
  parentTokens: number | null
  overlapTokens: number | null
  boundaryLookbackCharacters: number | null
  parentCount: number
  childCount: number
  records: DocumentChunk[]
  total: number
  page: number
  pageSize: number
}
export interface EmbeddingPreview {
  documentId: number
  documentVersion: number
  available: boolean
  indexed: boolean
  model: string | null
  dimensions: number | null
  childCount: number
  batchCount: number
  callCount: number
  totalTokens: number
  durationMs: number
  embeddedAt: string | null
}

/** 同时检查文档和任务状态，保留已安排自动重试的失败文档轮询。 */
export function shouldPollDocuments(records: DocumentRecord[], tasks: DocumentTask[]) {
  return (
    records.some(row => !['COMPLETED', 'FAILED'].includes(row.status)) ||
    tasks.some(task => task.status === 'PENDING' || task.status === 'RUNNING')
  )
}
