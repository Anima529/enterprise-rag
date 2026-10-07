import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'

const entries = new Map()
Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    /** 从测试内存中读取 sessionStorage 对应项。 */
    getItem: key => entries.get(key) ?? null,

    /** 将会话值写入测试内存，模拟 sessionStorage。 */
    setItem: (key, value) => entries.set(key, value),

    /** 删除测试内存中的会话项。 */
    removeItem: key => entries.delete(key),
  },
})
const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const { useAuthStore } = await server.ssrLoadModule('/src/stores/auth.ts')
const { http, onUnauthorized } = await server.ssrLoadModule('/src/api/http.ts')
const { safeRedirect } = await server.ssrLoadModule('/src/router/redirect.ts')
const user = {
  id: 101,
  username: 'alpha.user',
  departmentId: 10,
  departmentName: '研发部',
  roles: ['USER'],
  status: 'ACTIVE',
}
let redirects = 0

/** 重置 Pinia 和测试会话状态，隔离各用例。 */
beforeEach(() => {
  entries.clear()
  setActivePinia(createPinia())
  redirects = 0

  /** 累计未授权通知次数，验证会话清理只触发一次。 */
  onUnauthorized(() => redirects++)
})

/** 关闭本组用例创建的 Vite 测试服务。 */
after(async () => {
  await server.close()
})

/** 构造 Axios 成功响应，保留统一响应包装。 */
function success(config, data) {
  return { data: { code: 0, message: 'success', data }, status: 200, statusText: 'OK', headers: {}, config }
}

/** 构造含状态、错误码和 requestId 的 Axios 失败。 */
function reject(config, status) {
  throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, null, {
    data: { code: 'AUTH_401_UNAUTHENTICATED', message: '请先登录', requestId: 'test-request' },
    status,
    statusText: 'Error',
    headers: {},
    config,
  })
}

/** 验证只持久化 Token 并为认证请求添加 satoken 请求头。 */
test('shouldPersistOnlyTokenAndAttachSatokenHeaderToAuthenticatedRequests', async () => {
  const auth = useAuthStore()

  /** 模拟认证响应并检查请求使用的 Token，供会话状态断言。 */
  http.defaults.adapter = async config => {
    if (config.url === '/auth/login') {
      return success(config, { token: 'test-session', user })
    }
    assert.equal(config.headers.get('satoken'), 'test-session')
    return success(config, user)
  }
  await auth.login({ username: 'alpha.user', password: 'test-only-password' })
  assert.equal(auth.authenticated, true)
  assert.equal(auth.isAdmin, false)
  assert.deepEqual([...entries.keys()], ['rag.satoken'])
  assert.equal(entries.get('rag.satoken'), 'test-session')
  await http.get('/auth/me')
})

/** 验证刷新页面从后端恢复会话且并发恢复去重。 */
test('shouldRestoreFreshPageStateFromBackendAndDeduplicateConcurrentRestoration', async () => {
  entries.set('rag.satoken', 'test-restored-session')
  const auth = useAuthStore()
  let requests = 0

  /** 模拟认证响应并检查请求使用的 Token，供会话状态断言。 */
  http.defaults.adapter = async config => {
    requests++
    return success(config, user)
  }
  await Promise.all([auth.restore(), auth.restore()])
  assert.equal(requests, 1)
  assert.equal(auth.user.username, 'alpha.user')
  assert.equal(auth.restored, true)
  assert.equal(auth.authenticated, true)
})

/** 验证过期会话清理并通知路由，服务故障保留 Token。 */
test('shouldClearExpiredSessionAndNotifyRouterButKeepTokenOnServerFailure', async () => {
  entries.set('rag.satoken', 'test-expired-session')
  const auth = useAuthStore()

  /** 模拟服务器暂不可用，验证当前会话保留以便重试。 */
  http.defaults.adapter = async config => reject(config, 503)
  await assert.rejects(auth.restore())
  assert.equal(auth.token, 'test-expired-session')
  assert.equal(auth.restored, false)

  /** 模拟认证失败，验证对应登录或会话失效处理。 */
  http.defaults.adapter = async config => reject(config, 401)
  await auth.restore()
  assert.equal(auth.token, null)
  assert.equal(auth.user, null)
  assert.equal(entries.size, 0)
  assert.equal(redirects, 1)
})

/** 验证错误密码及旧未授权响应不破坏新会话。 */
test('shouldKeepCurrentSessionAfterWrongPasswordAndStaleUnauthorizedResponse', async () => {
  const auth = useAuthStore()

  /** 模拟登录成功并返回本用例指定的 Token 和用户。 */
  http.defaults.adapter = async config => success(config, { token: 'new-test-session', user })
  await auth.login({ username: 'alpha.user', password: 'test-only-password' })

  /** 模拟认证失败，验证对应登录或会话失效处理。 */
  http.defaults.adapter = async config => reject(config, 401)
  await assert.rejects(auth.login({ username: 'alpha.user', password: 'wrong' }))
  assert.equal(auth.token, 'new-test-session')
  const staleConfig = {
    url: '/auth/me',
    headers: {
      /** 返回请求携带的旧 Token，模拟迟到认证失败响应。 */
      get: () => 'old-test-session',
    },
  }

  /** 模拟旧 Token 的迟到失败，不应清理新的登录会话。 */
  http.defaults.adapter = async () => reject(staleConfig, 401)
  await assert.rejects(http.get('/auth/me'))
  assert.equal(auth.token, 'new-test-session')
  assert.equal(redirects, 0)
})

/** 验证清理后迟到的恢复请求不写回旧用户。 */
test('shouldNotRestoreStaleUserAfterSessionWasCleared', async () => {
  entries.set('rag.satoken', 'test-delayed-session')
  const auth = useAuthStore()
  let finish

  /** 保留可手动完成的请求，模拟会话清理后的迟到用户响应。 */
  http.defaults.adapter = config =>
    // Promise 由用例主动完成，确保响应在会话清理后到达。
    new Promise(resolve => {
      /** 用固定用户完成先前挂起的恢复请求。 */
      finish = () => resolve(success(config, user))
    })
  const restoration = auth.restore()
  // 等 Axios 派发请求后再清理会话，模拟真实的迟到响应。
  await new Promise(resolve => setImmediate(resolve))
  auth.clear()
  finish()
  await restoration
  assert.equal(auth.user, null)
  assert.equal(auth.authenticated, false)
})

/** 验证成功登出清理会话，服务器撤销失败时保留。 */
test('shouldClearAfterSuccessfulLogoutAndRetainSessionWhenServerCannotRevokeIt', async () => {
  const auth = useAuthStore()

  /** 模拟登录成功并返回本用例指定的 Token 和用户。 */
  http.defaults.adapter = async config => success(config, { token: 'test-logout-session', user })
  await auth.login({ username: 'alpha.user', password: 'test-only-password' })

  /** 模拟服务器暂不可用，验证当前会话保留以便重试。 */
  http.defaults.adapter = async config => reject(config, 503)
  await assert.rejects(auth.logout())
  assert.equal(auth.authenticated, true)

  /** 模拟服务器已成功撤销当前会话。 */
  http.defaults.adapter = async config => success(config, null)
  await auth.logout()
  assert.equal(auth.authenticated, false)
  assert.equal(entries.size, 0)
})

/** 验证登录后重定向只允许站内非登录路径。 */
test('shouldRestrictPostLoginRedirectToLocalNonLoginPaths', () => {
  assert.equal(safeRedirect('/admin/users?page=2#top'), '/admin/users?page=2#top')
  for (const unsafe of [
    'https://external.test',
    '//external.test',
    '/%2fexternal.test',
    '/\\external.test',
    '/login',
    '/login/?redirect=/login',
    '/profile\n',
    null,
    ['/admin/users'],
  ]) {
    assert.equal(safeRedirect(unsafe), '/profile')
  }
})
