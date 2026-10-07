import type { Citation } from './chat'

export type Complexity = 'SIMPLE' | 'STANDARD' | 'COMPLEX'
export interface RetrievalCandidate {
  hit: { chunkId: number; parentChunkId: number; documentId: number; documentVersion: number; documentName: string }
  vectorRank: number | null
  vectorScore: number | null
  keywordRank: number | null
  keywordScore: number | null
  rrfScore: number
  rerankScore: number | null
}
export interface RetrievalDebug {
  retrieval: {
    fused: RetrievalCandidate[]
    ranked: RetrievalCandidate[]
    selected: RetrievalCandidate[]
    vectorStatus: string
    keywordStatus: string
    rerankStatus: string
    vectorMs: number
    keywordMs: number
    rerankMs: number
  }
  context: { citations: Citation[]; evidence: Record<string, { content: string; metadataJson: string }> }
}
export interface AdaptiveTrace {
  plan: {
    query: string
    route: string
    reasonCode: string
    fallback: string
    queries: { question: string; vectorQuery: string }[]
  }
  planningMs: number
  retrievalMs: number
  searches: { vectorStatus: string; keywordStatus: string; rerankStatus: string }[]
}
