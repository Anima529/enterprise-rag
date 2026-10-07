/** 认证请求头和错误状态集中维护，保持既有登录失效处理边界。 */
export const httpProtocol = {
  apiBaseUrl: '/api/v1',
  authHeader: 'satoken',
  requestIdHeader: 'x-request-id',
  unauthorized: 401,
  forbidden: 403,
  post: 'POST',
} as const
export const accountRole = { user: 'USER', systemAdmin: 'SYSTEM_ADMIN' } as const
export const accountStatus = { active: 'ACTIVE', disabled: 'DISABLED' } as const
export const knowledgeBaseStatus = { enabled: 'ENABLED', disabled: 'DISABLED' } as const
export const permission = { read: 'READ', write: 'WRITE', admin: 'ADMIN' } as const
export const aclSubjectType = { user: 'USER', role: 'ROLE' } as const
export const taskStatus = { pending: 'PENDING', running: 'RUNNING', failed: 'FAILED', completed: 'COMPLETED' } as const
export const chunkType = { parent: 'PARENT', child: 'CHILD' } as const
export const previewType = { table: 'TABLE', text: 'TEXT' } as const
export const priceBasis = { tokens: 'TOKENS', call: 'CALL', units: 'UNITS' } as const
export const redisStatus = { rebuilt: 'REBUILT', unavailable: 'UNAVAILABLE' } as const
export const retrievalComplexity = { simple: 'SIMPLE', standard: 'STANDARD', complex: 'COMPLEX' } as const
export const documentFormat = { pdf: 'PDF', doc: 'DOC', docx: 'DOCX', xlsx: 'XLSX', txt: 'TXT', md: 'MD' } as const
export const apiErrorCode = {
  knowledgeBaseNotFound: 'KB_404_NOT_FOUND',
  knowledgeBaseDisabled: 'KB_409_DISABLED',
} as const
