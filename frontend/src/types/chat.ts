import type { SourcePreviewBlock, SourceTable } from './source-preview'

export interface Conversation {
  id: number
  title: string
  createdAt: string
  updatedAt: string
}
export type CitationTable = SourceTable
export interface Citation {
  citationId: string
  documentId: number
  documentVersion: number
  documentName: string
  page: number | null
  chapter: string | null
  section: string | null
  lineStart: number | null
  lineEnd: number | null
  chunkId: number
  snippet: string
  tables?: CitationTable[]
  preview?: SourcePreviewBlock[]
}
export interface ChatMessage {
  id: number
  conversationId: number
  role: 'USER' | 'ASSISTANT'
  content: string
  requestId: string
  traceId: string
  status: 'GENERATING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  errorCode: string | null
  confidenceLevel: string
  faithfulnessStatus: string
  inputTokens: number | null
  outputTokens: number | null
  createdAt: string
  citations: Citation[]
  verificationDetails?: Verification
  estimatedCost?: number | null
}
export interface Verification {
  status: string
  confidenceLevel: string
  coverage: number
  unsupportedClaims: string[]
  conflictingEvidence: boolean
  reasonCode: string
}
export interface CitationLocation {
  page: number | null
  chapter: string | null
  section: string | null
  sheetName: string | null
  rowStart: number | null
  rowEnd: number | null
  lineStart: number | null
  lineEnd: number | null
  blockIndex: number | null
  charStart: number | null
  charEnd: number | null
}
export interface CitationSource {
  citation: Citation
  format: 'PDF' | 'DOC' | 'DOCX' | 'XLSX' | 'TXT' | 'MD'
  locations: CitationLocation[]
  fullEvidence: boolean
}
export type StreamEvent =
  | { type: 'status'; data: { stage: string; message: string } }
  | { type: 'content'; data: { delta: string } }
  | { type: 'citation'; data: Citation }
  | {
      type: 'usage'
      data: { inputTokens: number | null; outputTokens: number | null; estimatedCost: number | null; currency: string }
    }
  | { type: 'verification'; data: { status: string; confidenceLevel: string } & Partial<Verification> }
  | { type: 'done'; data: { messageId: number; requestId: string; traceId: string; finishReason: string } }
  | { type: 'error'; data: { code: string; message: string; requestId: string } }
