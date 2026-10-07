export type DependencyStatus = 'UP' | 'DOWN' | 'UNKNOWN' | 'OUT_OF_SERVICE'
export interface SystemStatus {
  service: string
  checkedAt: string
  database: { databaseVersion: string; vectorVersion: string; migrations: number }
  dependencies: { postgres: DependencyStatus; redis: DependencyStatus; minio: DependencyStatus }
}
