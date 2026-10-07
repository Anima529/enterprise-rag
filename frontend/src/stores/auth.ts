import { accountRole, httpProtocol } from '../constants/contracts'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '../api/auth'
import { ApiRequestError } from '../api/http'
import type { LoginRequest, User } from '../types/auth'

const storageKey = 'rag.satoken'

/** 维护当前 Token、有效用户和会话恢复状态。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(sessionStorage.getItem(storageKey))
  const user = ref<User | null>(null)
  const restored = ref(!token.value)

  /** 要求 Token 和用户信息同时存在才视为已登录。 */
  const authenticated = computed(() => !!token.value && !!user.value)

  /** 依据最新用户角色判断管理员入口是否可见。 */
  const isAdmin = computed(() => user.value?.roles.includes(accountRole.systemAdmin) ?? false)
  let pendingRestore: Promise<void> | null = null
  let generation = 0

  /** 推进会话代次并清理内存与 sessionStorage，阻止旧恢复请求写回。 */
  function clear() {
    generation++
    token.value = null
    user.value = null
    restored.value = true
    pendingRestore = null
    sessionStorage.removeItem(storageKey)
  }

  /** 登录成功后保存 Token 和用户，并使旧会话请求失效。 */
  async function login(request: LoginRequest) {
    const loginResponse = await authApi.login(request)
    generation++
    token.value = loginResponse.token
    user.value = loginResponse.user
    restored.value = true
    sessionStorage.setItem(storageKey, loginResponse.token)
  }

  /** 仅在需要时恢复会话，复用并发中的同一次恢复请求。 */
  async function restore() {
    if (restored.value || !token.value) {
      return
    }
    if (pendingRestore) {
      return pendingRestore
    }
    const restoreGeneration = generation
    const restoreRequest = restoreSession(restoreGeneration)
    pendingRestore = restoreRequest
    return restoreRequest
  }

  /** 读取当前用户，仅在代次未变化时更新状态或处理认证失效。 */
  async function restoreSession(restoreGeneration: number) {
    // 登录或登出会推进代次，旧请求的迟到结果不能覆盖新的会话状态。
    try {
      const currentUser = await authApi.me()
      if (restoreGeneration === generation) {
        user.value = currentUser
        restored.value = true
      }
    } catch (error) {
      if (!(error instanceof ApiRequestError && error.status === httpProtocol.unauthorized)) {
        throw error
      }
      if (restoreGeneration === generation) {
        clear()
      }
    } finally {
      if (restoreGeneration === generation) {
        pendingRestore = null
      }
    }
  }

  /** 服务器确认撤销或已失效后清理会话，服务故障时保留重试机会。 */
  async function logout() {
    try {
      await authApi.logout()
    } catch (error) {
      if (!(error instanceof ApiRequestError && error.status === httpProtocol.unauthorized)) {
        throw error
      }
    }
    clear()
  }

  return { token, user, restored, authenticated, isAdmin, clear, login, restore, logout }
})
