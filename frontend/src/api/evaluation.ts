import { http } from './http'
import type { ApiSuccess } from '../types/contracts'
import type {
  EvaluationCase,
  EvaluationDataset,
  EvaluationDetail,
  EvaluationMode,
  EvaluationRun,
} from '../types/evaluation'

/** 查询管理员可见的不可变数据集，权限仍由后端检查。 */
export async function listDatasets(): Promise<EvaluationDataset[]> {
  const response = await http.get<ApiSuccess<EvaluationDataset[]>>('/admin/evaluation/datasets')
  return response.data.data
}

/** 保存固定身份及相关 Child 标注的新数据集。 */
export async function createDataset(
  name: string,
  description: string,
  evaluationUserId: number,
  cases: EvaluationCase[],
): Promise<number> {
  const response = await http.post<ApiSuccess<{ id: number }>>('/admin/evaluation/datasets', {
    name,
    description,
    evaluationUserId,
    cases,
  })
  return response.data.data.id
}

/** 启动异步实验，后台会保存当前配置、来源和权限快照。 */
export async function startEvaluation(datasetId: number, name: string, mode: EvaluationMode): Promise<number> {
  const response = await http.post<ApiSuccess<{ id: number }>>(`/admin/evaluation/datasets/${datasetId}/runs`, {
    name,
    mode,
  })
  return response.data.data.id
}

/** 读取同一数据集全部近期实验，供轮询和对比使用。 */
export async function listEvaluationRuns(datasetId: number): Promise<EvaluationRun[]> {
  const response = await http.get<ApiSuccess<EvaluationRun[]>>(`/admin/evaluation/datasets/${datasetId}/runs`)
  return response.data.data
}

/** 读取完整配置和每题排序、回答、核验、错误及 trace。 */
export async function getEvaluationRun(runId: number): Promise<EvaluationDetail> {
  const response = await http.get<ApiSuccess<EvaluationDetail>>(`/admin/evaluation/runs/${runId}`)
  return response.data.data
}
