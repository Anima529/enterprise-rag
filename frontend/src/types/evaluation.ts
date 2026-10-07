export type EvaluationMode = 'VECTOR' | 'HYBRID' | 'HYBRID_RERANK'
export type EvaluationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface EvaluationCase {
  question: string
  knowledgeBaseIds: number[]
  expectedChunkIds: number[]
  referenceAnswer: string
  history: string
  expectDenied: boolean
  category: string
}

export interface EvaluationDataset {
  id: number
  name: string
  description: string
  evaluation_user_id: number
  case_count: number
  created_at: string
}

export interface EvaluationSummary {
  caseCount: number
  retrievalDenominator: number
  metricK: number
  hitRate: number | null
  recall: number | null
  mrr: number | null
  faithfulness: number | null
  faithfulnessDenominator: number
  answerRelevance: number | null
  answerRelevanceDenominator: number
  errors: number
  permissionCases: number
  permissionPassed: number
}

export interface EvaluationRun {
  id: number
  name: string
  mode: EvaluationMode
  status: EvaluationStatus
  error_code: string | null
  started_at: string | null
  finished_at: string | null
  summary: EvaluationSummary | null
}

export interface EvaluationDetail extends EvaluationRun {
  dataset_id: number
  config_snapshot: {
    configurationHash: string
    evaluationUserId: number
    scopeFingerprints: Record<string, string>
    documents: unknown[]
    [key: string]: unknown
  }
  results: { case_id: number; snapshot: unknown }[]
}
