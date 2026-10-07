import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'

Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    /** 返回日志请求使用的固定会话，验证 Header 认证。 */
    getItem: () => 'test-log-token',
    /** 此测试无需修改固定会话。 */
    setItem() {
    },
    /** 此测试不执行会话删除。 */
    removeItem() {
    },
  },
})

const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const { listAuditLogs, listRequestLogs } = await server.ssrLoadModule('/src/api/adminLog.ts')
const { http, ApiRequestError } = await server.ssrLoadModule('/src/api/http.ts')

/** 每例重新创建认证 Store，隔离请求会话状态。 */
beforeEach(() => {
  setActivePinia(createPinia())
})

/** 释放本组用例创建的 Vite 测试服务。 */
after(async () => {
  await server.close()
})

/** 两类日志沿用认证、日期和分页协议，并原样保留未知元数据。 */
test('shouldQueryAuthenticatedLogPagesAndPreserveNullMetadata', async () => {
  const query = { from: '2026-10-01', to: '2026-10-06', userId: 5, requestId: 'request-1' }
  const records = [{ userId: null, username: null, requestId: 'request-1', traceId: null }]
  /** 检查精确筛选和统一分页参数，返回真实包装结构。 */
  http.defaults.adapter = async config => {
    assert.equal(config.headers.get('satoken'), 'test-log-token')
    assert.equal(config.method, 'get')
    assert.deepEqual(config.params, { ...query, page: 2, pageSize: 20 })
    assert.ok(['/admin/logs/audit', '/admin/logs/requests'].includes(config.url))
    return {
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: { code: 0, message: 'success', data: { records, total: 21, page: 2, pageSize: 20 } },
    }
  }
  for (const load of [listAuditLogs, listRequestLogs]) {
    const logs = await load(query, 2)
    assert.deepEqual(logs.records, records)
    assert.equal(logs.total, 21)
  }
})

/** 管理员角色撤销错误保留 requestId，页面能显示明确诊断信息。 */
test('shouldPropagateForbiddenLogQueryWithRequestId', async () => {
  /** 模拟后端拒绝普通身份读取全局日志。 */
  http.defaults.adapter = async config => {
    throw new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, null, {
      config,
      status: 403,
      statusText: 'Forbidden',
      headers: {},
      data: { code: 'AUTH_403_FORBIDDEN', message: '无权访问', requestId: 'denied-request' },
    })
  }
  /** 核对被拒绝请求仍保留服务端追踪标识和 HTTP 状态。 */
  const isForbidden = cause => {
    return cause instanceof ApiRequestError && cause.requestId === 'denied-request' && cause.status === 403
  }
  await assert.rejects(listRequestLogs({ from: '2026-10-01', to: '2026-10-06', status: 'FAILED' }, 1), isForbidden)
})
