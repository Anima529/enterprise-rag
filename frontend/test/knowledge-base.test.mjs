import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'

Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    /** 返回固定测试 Token，供认证请求头断言使用。 */
    getItem: () => 'test-kb-token',

    /** 该组用例只读取固定 Token，存储写入无需模拟。 */
    setItem() {
    },

    /** 该组用例不维护会话状态，删除存储项无需模拟。 */
    removeItem() {
    },
  },
})
const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const api = await server.ssrLoadModule('/src/api/knowledgeBase.ts')
const { http, ApiRequestError } = await server.ssrLoadModule('/src/api/http.ts')
const { useAuthStore } = await server.ssrLoadModule('/src/stores/auth.ts')

/** 重置 Pinia 和测试会话状态，隔离各用例。 */
beforeEach(() => setActivePinia(createPinia()))

/** 关闭本组用例创建的 Vite 测试服务。 */
after(async () => server.close())

/** 为请求替身构造符合接口协议的成功响应。 */
function response(config, data) {
  return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, message: 'success', data } }
}

/** 验证仅显式管理模式使用管理员路径并传递分页。 */
test('shouldUseAdministratorPathsOnlyWhenManagementIsExplicitAndPassPagination', async () => {
  const seen = []

  /** 检查知识库或 ACL 请求协议并返回预设响应。 */
  http.defaults.adapter = async config => {
    seen.push(config)
    assert.equal(config.headers.get('satoken'), 'test-kb-token')
    return response(config, { records: [], total: 0, page: config.params?.page, pageSize: 20 })
  }
  await api.listKnowledgeBases(2)
  await api.listKnowledgeBases(3, true)
  await api.getKnowledgeBase(42, true)
  assert.equal(seen[0].url, '/knowledge-bases')
  assert.deepEqual(seen[0].params, { page: 2, pageSize: 20 })
  assert.equal(seen[1].url, '/admin/knowledge-bases')
  assert.equal(seen[2].url, '/admin/knowledge-bases/42')
})

/** 验证 ACL 完整替换、空集合撤销及字符串主体 ID。 */
test('shouldSendCompleteAclReplacementIncludingEmptyRevocationAndKeepSubjectIdsAsStrings', async () => {
  const seen = []

  /** 检查知识库或 ACL 请求协议并返回预设响应。 */
  http.defaults.adapter = async config => {
    seen.push(config)
    return response(config, [])
  }
  await api.replaceAcl(42, [{ subjectType: 'USER', subjectId: '101', permission: 'WRITE' }])
  await api.replaceAcl(42, [], true)
  assert.equal(seen[0].url, '/knowledge-bases/42/acl')
  assert.equal(seen[0].method, 'put')
  assert.deepEqual(JSON.parse(seen[0].data), {
    entries: [{ subjectType: 'USER', subjectId: '101', permission: 'WRITE' }],
  })
  assert.equal(seen[1].url, '/admin/knowledge-bases/42/acl')
  assert.deepEqual(JSON.parse(seen[1].data), { entries: [] })
})

/** 验证撤销和停用错误保留 requestId 且不退出登录。 */
test('shouldSurfaceRevocationAndDisabledErrorsWithRequestIdWithoutSigningOut', async () => {
  const auth = useAuthStore()
  for (const [status, code, message] of [
    [403, 'KB_403_FORBIDDEN', '无权访问该知识库'],
    [409, 'KB_409_DISABLED', '知识库已停用'],
  ]) {
    /** 检查知识库或 ACL 请求协议并返回预设响应。 */
    http.defaults.adapter = async config => {
      throw new AxiosError('denied', 'ERR_BAD_REQUEST', config, null, {
        config,
        status,
        statusText: 'Error',
        headers: {},
        data: { code, message, requestId: 'kb-test-request' },
      })
    }

    /** 核对权限撤销或停用错误的状态、提示和 requestId。 */
    await assert.rejects(api.getKnowledgeBase(42), error => {
      assert.ok(error instanceof ApiRequestError)
      assert.equal(error.status, status)
      assert.equal(error.requestId, 'kb-test-request')
      assert.equal(error.message, message)
      return true
    })
    assert.equal(auth.token, 'test-kb-token')
  }
})
