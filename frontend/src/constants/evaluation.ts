export const evaluationModes = Object.freeze({ vector: 'VECTOR', hybrid: 'HYBRID', rerank: 'HYBRID_RERANK' } as const)
export const evaluationStates = Object.freeze({
  pending: 'PENDING',
  running: 'RUNNING',
  completed: 'COMPLETED',
  failed: 'FAILED',
} as const)
export const evaluationScoreDecimals = 3
export const evaluationPollIntervalMs = 2000
export const evaluationCaseTemplate = [
  {
    question: '工作满五年的员工年假是多少天？',
    knowledgeBaseIds: [1],
    expectedChunkIds: [],
    referenceAnswer: '十天',
    history: '',
    expectDenied: false,
    category: '精确术语',
  },
]
