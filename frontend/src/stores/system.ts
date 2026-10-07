import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getSystemStatus } from '../api/system'
import { ApiRequestError } from '../api/http'
import type { SystemStatus } from '../types/system'

/** 维护基础设施状态、加载状态和可追踪错误。 */
export const useSystemStore = defineStore('system', () => {
  const status = ref<SystemStatus | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const requestId = ref('')
  const traceId = ref('')

  /** 根据后端返回的基础设施状态计算整体可用性。 */
  const healthy = computed(
    () => status.value !== null && Object.values(status.value.dependencies).every(value => value === 'UP'),
  )

  /** 刷新系统状态和追踪 ID，失败时保存可展示的提示。 */
  async function refresh() {
    if (loading.value) {
      return
    }
    loading.value = true
    error.value = null
    status.value = null
    requestId.value = ''
    traceId.value = ''
    try {
      const systemStatusResponse = await getSystemStatus()
      status.value = systemStatusResponse.status
      requestId.value = systemStatusResponse.requestId
      traceId.value = systemStatusResponse.traceId
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '系统状态获取失败。'
      if (cause instanceof ApiRequestError) {
        requestId.value = cause.requestId ?? ''
      }
    } finally {
      loading.value = false
    }
  }
  return { status, loading, error, requestId, traceId, healthy, refresh }
})
