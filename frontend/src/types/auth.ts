export interface User {
  id: number
  username: string
  departmentId: number
  departmentName: string
  roles: ('USER' | 'SYSTEM_ADMIN')[]
  status: 'ACTIVE' | 'DISABLED'
}

export interface LoginRequest {
  username: string
  password: string
}
export interface LoginResponse {
  token: string
  user: User
}
export interface Department {
  id: number
  code: string
  name: string
  status: 'ACTIVE' | 'DISABLED'
}
export type { PageResponse as Page } from './contracts'
