import type { Permission, SubjectType } from './contracts'

export interface KnowledgeBase {
  id: number
  name: string
  description: string
  departmentId: number
  departmentName: string
  ownerUserId: number
  ownerUsername: string
  visibility: 'PRIVATE'
  status: 'ENABLED' | 'DISABLED'
  embeddingModel: string
  embeddingDimensions: number
  generationModel: string
  createdAt: string
  updatedAt: string
  permission: Permission
}
export interface CreateKnowledgeBase {
  name: string
  description: string
  departmentId: number
  ownerUserId?: number
}
export interface UpdateKnowledgeBase {
  name: string
  description: string
  status: KnowledgeBase['status']
}
export interface AclEntry {
  subjectType: SubjectType
  subjectId: string
  permission: Permission
}
