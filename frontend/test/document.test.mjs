import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'

Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    /** 返回固定测试 Token，供认证请求头断言使用。 */
    getItem: () => 'test-document-token',

    /** 该组用例只读取固定 Token，存储写入无需模拟。 */
    setItem() {
    },

    /** 该组用例不维护会话状态，删除存储项无需模拟。 */
    removeItem() {
    },
  },
})
const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const api = await server.ssrLoadModule('/src/api/document.ts')
const { http, ApiRequestError } = await server.ssrLoadModule('/src/api/http.ts')
const { documentStatus, taskStatus, shouldPollDocuments } = await server.ssrLoadModule('/src/types/document.ts')

/** 重置 Pinia 和测试会话状态，隔离各用例。 */
beforeEach(() => setActivePinia(createPinia()))

/** 关闭本组用例创建的 Vite 测试服务。 */
after(async () => server.close())

/** 为请求替身构造符合接口协议的成功响应。 */
function response(config, data) {
  return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, message: 'success', data } }
}

/** 验证 multipart 上传包含认证和进度回调，并返回受理的文档 ID。 */
test('shouldUploadMultipartWithHeaderAuthProgressAndAcceptedDocumentId', async () => {
  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.headers.get('satoken'), 'test-document-token')
    assert.equal(config.url, '/knowledge-bases/42/documents')
    assert.equal(config.method, 'post')
    assert.ok(config.data instanceof FormData)
    assert.equal(config.data.get('file').name, '中文.md')
    assert.equal(await config.data.get('file').text(), '# 中文文档')
    config.onUploadProgress({ loaded: 5, total: 10 })
    return response(config, { documentId: 12, documentVersion: 1, status: 'UPLOADED' })
  }
  let progress = 0

  /** 记录上传进度回调，供百分比断言。 */
  const result = await api.uploadDocument(42, new File(['# 中文文档'], '中文.md', { type: 'text/plain' }), value => {
    progress = value
  })
  assert.equal(progress, 50)
  assert.deepEqual(result, { documentId: 12, documentVersion: 1, status: 'UPLOADED' })
})

/** 验证文档元数据及任务分页路径和最新版本下载请求。 */
test('shouldUsePaginatedMetadataAndTaskRoutesAndRequestFreshVersionedDownload', async () => {
  const seen = []

  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    seen.push(config)
    return response(config, {})
  }
  await api.listDocuments(42, 3)
  await api.getUploadPolicy(42)
  await api.getDocument(12)
  await api.getDocumentTasks(12)
  await api.getDocumentDownload(12, 1)
  await api.deleteDocument(12)
  assert.deepEqual(
    seen.map(config => config.url),
    [
      '/knowledge-bases/42/documents',
      '/knowledge-bases/42/documents/upload-policy',
      '/documents/12',
      '/documents/12/tasks',
      '/documents/12/download',
      '/documents/12',
    ],
  )
  assert.deepEqual(seen[0].params, { page: 3, pageSize: 20 })
  assert.deepEqual(seen[4].params, { version: 1 })
  assert.equal(seen[5].method, 'delete')
})

/** 验证重复内容、大小限制及权限撤销错误保留 requestId。 */
test('shouldPreserveDuplicateContentSizeAndRevocationErrorsWithRequestId', async () => {
  for (const [status, code] of [
    [409, 'DOC_409_DUPLICATE'],
    [413, 'DOC_413_TOO_LARGE'],
    [422, 'DOC_422_INVALID_CONTENT'],
    [403, 'KB_403_FORBIDDEN'],
  ]) {
    /** 检查本用例的请求协议并返回预设成功或错误响应。 */
    http.defaults.adapter = async config => {
      throw new AxiosError('denied', 'ERR_BAD_REQUEST', config, null, {
        config,
        status,
        statusText: 'Error',
        headers: {},
        data: { code, message: '上传或下载被拒绝', requestId: 'doc-request' },
      })
    }
    await assert.rejects(
      api.getDocumentDownload(12),
      error =>
        error instanceof ApiRequestError &&
        error.status === status &&
        error.requestId === 'doc-request' &&
        error.code === code,
    )
  }
})

/** 验证上传保持待处理状态并覆盖全部文档和任务状态。 */
test('shouldLabelUploadAsPendingAndRepresentAllDocumentAndTaskStates', () => {
  assert.match(documentStatus.UPLOADED.label, /待处理/)
  assert.deepEqual(Object.keys(documentStatus), [
    'UPLOADED',
    'PARSING',
    'CHUNKING',
    'EMBEDDING',
    'INDEXING',
    'COMPLETED',
    'FAILED',
  ])
  assert.deepEqual(Object.keys(taskStatus), ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'])
})

/** 验证任务重试和阶段分页使用认证请求头。 */
test('shouldRequestFailedTaskRetryAndPaginatedStepHistoryWithHeaderAuth', async () => {
  const seen = []

  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.headers.get('satoken'), 'test-document-token')
    seen.push(config)
    return response(config, { records: [], total: 0 })
  }
  await api.retryDocumentTask(12, 33)
  await api.getDocumentTaskSteps(12, 33, 2)
  assert.equal(seen[0].url, '/documents/12/tasks/33/retry')
  assert.equal(seen[0].method, 'post')
  assert.equal(seen[1].url, '/documents/12/tasks/33/steps')
  assert.deepEqual(seen[1].params, { page: 2, pageSize: 100 })
})

/** 验证已安排重试的失败文档继续轮询，终态停止。 */
test('shouldKeepPollingFailedDocumentsWithScheduledRetryAndStopAtTerminalTasks', () => {
  assert.equal(shouldPollDocuments([{ status: 'FAILED' }], [{ status: 'PENDING' }]), true)
  assert.equal(shouldPollDocuments([{ status: 'FAILED' }], [{ status: 'RUNNING' }]), true)
  assert.equal(shouldPollDocuments([{ status: 'FAILED' }], [{ status: 'FAILED' }]), false)
  assert.equal(shouldPollDocuments([{ status: 'COMPLETED' }], [{ status: 'COMPLETED' }]), false)
})

/** 验证授权版本向量汇总不包含原始向量。 */
test('shouldReadAuthorizedVersionedEmbeddingSummaryWithoutExposingRawVectors', async () => {
  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.url, '/documents/12/embedding')
    assert.equal(config.headers.get('satoken'), 'test-document-token')
    assert.deepEqual(config.params, { version: 1 })
    return response(config, {
      available: true,
      indexed: true,
      model: 'text-embedding-v3',
      dimensions: 1024,
      childCount: 12,
      batchCount: 2,
      callCount: 3,
      totalTokens: 800,
      durationMs: 200,
    })
  }
  const summary = await api.getDocumentEmbedding(12, 1)
  assert.equal(summary.indexed, true)
  assert.equal(summary.callCount, 3)
  assert.equal(summary.embedding, undefined)
})

/** 验证带认证的版本解析预览分页。 */
test('shouldReadVersionedPaginatedPlainTextParserPreviewWithHeaderAuth', async () => {
  const text = '<script>fetch("https://invalid.example/")</script>\n```java\nint x = 1;\n```'

  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.url, '/documents/12/parsed-blocks')
    assert.equal(config.headers.get('satoken'), 'test-document-token')
    assert.deepEqual(config.params, { page: 2, pageSize: 20, version: 1 })
    return response(config, {
      available: true,
      records: [{ blockIndex: 20, text, metadata: { lineStart: 4, lineEnd: 8 } }],
    })
  }
  const preview = await api.getParsedBlocks(12, 2, 1)
  assert.equal(preview.records[0].text, text)
  assert.deepEqual(preview.records[0].metadata, { lineStart: 4, lineEnd: 8 })
})

/** 验证版本 Chunk 类型筛选及父块来源元数据保留。 */
test('shouldReadVersionedChunkPreviewWithTypeFilterAndPreserveParentSourceMetadata', async () => {
  /** 检查本用例的请求协议并返回预设成功或错误响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.url, '/documents/12/chunks')
    assert.equal(config.headers.get('satoken'), 'test-document-token')
    assert.deepEqual(config.params, { page: 2, pageSize: 20, version: 1, chunkType: 'CHILD' })
    return response(config, {
      available: true,
      boundaryLookbackCharacters: 100,
      records: [
        {
          id: 42,
          parentChunkId: 21,
          content: '<script>untrusted</script>',
          metadata: { sources: [{ blockIndex: 0, charStart: 2, charEnd: 8, metadata: { lineStart: 4, lineEnd: 5 } }] },
        },
      ],
    })
  }
  const preview = await api.getDocumentChunks(12, 2, 1, 'CHILD')
  assert.equal(preview.boundaryLookbackCharacters, 100)
  assert.equal(preview.records[0].parentChunkId, 21)
  assert.equal(preview.records[0].content, '<script>untrusted</script>')
  assert.equal(preview.records[0].metadata.sources[0].metadata.lineStart, 4)
})
