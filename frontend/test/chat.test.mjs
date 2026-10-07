import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    /** 返回固定测试 Token，供认证请求头断言使用。 */
    getItem: () => 'chat-test-token',

    /** 该组用例只读取固定 Token，存储写入无需模拟。 */
    setItem() {
    },

    /** 该组用例不维护会话状态，删除存储项无需模拟。 */
    removeItem() {
    },
  },
})
const vite = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const { SseParser } = await vite.ssrLoadModule('/src/api/sse.ts')
const { streamChat, cancelChat } = await vite.ssrLoadModule('/src/api/chat.ts')
const { renderMarkdown } = await vite.ssrLoadModule('/src/utils/markdown.ts')
const { default: CitationContent } = await vite.ssrLoadModule('/src/components/CitationContent.vue')
const { http } = await vite.ssrLoadModule('/src/api/http.ts')
const { getCitationSource, getCitationPdf } = await vite.ssrLoadModule('/src/api/conversation.ts')
const { debugRetrieval } = await vite.ssrLoadModule('/src/api/retrieval.ts')
const { getUsage, rebuildUsage } = await vite.ssrLoadModule('/src/api/usage.ts')
const { default: VerificationNotice } = await vite.ssrLoadModule('/src/components/VerificationNotice.vue')
const originalFetch = globalThis.fetch
const { positiveInteger, clientConfig } = await vite.ssrLoadModule('/src/config/client.ts')

/** 时限配置保留默认值，非法数字在创建请求或计时器前拒绝。 */
test('shouldRejectMalformedClientBudgetsAndPreserveDefaults', () => {
  assert.equal(positiveInteger(undefined, 12000, 'TIMEOUT'), 12000)
  assert.equal(positiveInteger('45000', 12000, 'TIMEOUT'), 45000)
  for (const value of ['0', '-1', 'NaN', '1.5', '1e3', '9007199254740992']) {
    assert.throws(() => positiveInteger(value, 12000, 'TIMEOUT'), /必须为正整数/)
  }
  assert.equal(clientConfig.maxSseEventCharacters, 1048576)
})

/** SSE 单事件限制来自客户端配置，超限帧在交付业务数据前拒绝。 */
test('shouldEnforceConfiguredSseEventCharacterLimit', () => {
  const frames = []
  const parser = new SseParser(frame => frames.push(frame))
  assert.throws(() => parser.push('x'.repeat(clientConfig.maxSseEventCharacters + 1)), /exceeds size limit/)
  assert.deepEqual(frames, [])
})

/** 核验失败、未完成及流式阶段均不能显示证据支持或 HIGH 的暗示。 */
test('shouldDisplayExplicitFinalVerificationAndUnknownForUnfinishedAnswers', async () => {
  for (const [status, generating, expected] of [
    ['UNKNOWN', false, '尚未核实'],
    ['UNSUPPORTED', false, '缺少证据支持'],
    ['PARTIALLY_SUPPORTED', false, '仅部分内容'],
    ['SUPPORTED', false, '回答获证据支持'],
    ['SUPPORTED', true, '尚在生成或核验中'],
  ]) {
    const html = await renderToString(createSSRApp(VerificationNotice, { status, generating, confidence: 'LOW' }))
    assert.ok(html.includes(expected))
    if (status !== 'SUPPORTED' || generating) {
      assert.ok(!html.includes('回答获证据支持'))
    }
  }
})

/** 用量接口保留后端身份边界，普通请求不提交 userId 或部门选择。 */
test('shouldUseSelfAndAdministratorUsageApisWithoutClientOwnerOverrides', async () => {
  const paths = []
  /** 记录用量路径和日期参数，返回可核对的后端快照。 */
  http.defaults.adapter = async config => {
    paths.push(config.url)
    if (config.params) {
      assert.deepEqual(config.params, { from: '2026-10-05', to: '2026-10-05' })
    }
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, data: { redisStatus: 'REBUILT' } } }
  }
  await getUsage('2026-10-05', '2026-10-05', false)
  await getUsage('2026-10-05', '2026-10-05', true)
  assert.equal((await rebuildUsage()).redisStatus, 'REBUILT')
  assert.deepEqual(paths, ['/usage/dashboard', '/admin/usage/dashboard', '/admin/usage/rebuild'])
})

/** 重置 Pinia 和测试会话状态，隔离各用例。 */
beforeEach(() => setActivePinia(createPinia()))

/** 恢复 fetch 替身并关闭本组 Vite 测试服务。 */
after(async () => {
  globalThis.fetch = originalFetch
  await vite.close()
})

/** 构造可按分片读取的 SSE 响应替身。 */
function streaming(text, chunkSize = 1) {
  const bytes = new TextEncoder().encode(text)
  return new Response(
    new ReadableStream({
      /** 启动测试 SSE 请求并收集已交付事件。 */
      start(controller) {
        for (let i = 0; i < bytes.length; i += chunkSize) {
          controller.enqueue(bytes.slice(i, i + chunkSize))
        }
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream; charset=utf-8', 'x-request-id': 'test-request' } },
  )
}

/** 验证混合换行、多行数据、心跳及重复 SSE ID 处理。 */
test('shouldParseMixedLineEndingsMultilineDataHeartbeatsAndDuplicateIds', () => {
  const frames = []
  const parser = new SseParser(frame => frames.push(frame))
  const input =
    ': heartbeat\r\nid: r:1\revent: content\ndata: {"delta":\r\ndata: "中文"}\r\n\r\nid: r:1\nevent: content\ndata: {"delta":"重复"}\n\n'
  for (const character of input) {
    parser.push(character)
  }
  assert.equal(frames.length, 1)
  assert.equal(frames[0].id, 'r:1')
  assert.equal(JSON.parse(frames[0].data).delta, '中文')
})

/** 验证跨字节中文解码、请求后置 Header 和单一完成事件。 */
test('shouldDecodeChineseAcrossEveryByteSendPostHeaderAndDeliverOneDone', async () => {
  const events = []
  let request

  /** 提供本用例指定的流响应，隔离真实网络与模型调用。 */
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/chat/stream')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers.satoken, 'chat-test-token')
    assert.equal(JSON.parse(options.body).conversationId, 3)
    return streaming(
      'id:r:1\nevent:content\ndata:{"delta":"中文😀"}\n\nid:r:1\nevent:content\ndata:{"delta":"重复"}\n\nid:r:2\nevent:done\ndata:{"messageId":9,"finishReason":"COMPLETED"}\n\n',
    )
  }

  /** 交付测试事件与请求 ID，供流顺序和终态断言。 */
  await streamChat(
    { conversationId: 3, knowledgeBaseIds: [1], message: '问题' },
    new AbortController().signal,
    event => events.push(event),
    id => {
      request = id
    },
  )
  assert.equal(request, 'test-request')
  assert.deepEqual(
    events.map(event => event.type),
    ['content', 'done'],
  )
  assert.equal(events[0].data.delta, '中文😀')
})

/** 验证意外断流被检测且不重试生成。 */
test('shouldDetectInterruptedStreamWithoutRetryingOrDuplicatingGeneration', async () => {
  let calls = 0

  /** 提供本用例指定的流响应，隔离真实网络与模型调用。 */
  globalThis.fetch = async () => {
    calls++
    return streaming('event:content\ndata:{"delta":"部分"}\n\n')
  }
  await assert.rejects(
    /** 仅验证失败协议，空回调有意不改变测试状态。 */
    streamChat(
      { conversationId: 1, knowledgeBaseIds: [1], message: '问' },
      new AbortController().signal,
      () => {
      },
      () => {
      },
    ),
    /连接中断/,
  )
  assert.equal(calls, 1)
})

/** 验证流前和流中结构化错误均保留。 */
test('shouldPreserveStructuredPreflightAndMidstreamErrors', async () => {
  /** 提供本用例指定的流响应，隔离真实网络与模型调用。 */
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ code: 'KB_403_FORBIDDEN', message: '无权', requestId: 'r' }), { status: 403 })
  await assert.rejects(
    /** 仅验证失败协议，空回调有意不改变测试状态。 */
    streamChat(
      { conversationId: 1, knowledgeBaseIds: [2], message: '问' },
      new AbortController().signal,
      () => {
      },
      () => {
      },
    ),
    error => error.status === 403 && error.requestId === 'r',
  )
  const events = []

  /** 提供本用例指定的流响应，隔离真实网络与模型调用。 */
  globalThis.fetch = async () =>
    streaming(
      'event:content\ndata:{"delta":"部分"}\n\nevent:error\ndata:{"code":"RAG_503_MODEL_UNAVAILABLE","message":"不可用"}\n\n',
    )

  /** 交付测试事件与请求 ID，供流顺序和终态断言。 */
  await streamChat(
    { conversationId: 1, knowledgeBaseIds: [1], message: '问' },
    new AbortController().signal,
    event => events.push(event),
    () => {
    },
  )
  assert.deepEqual(
    events.map(event => event.type),
    ['content', 'error'],
  )
})

/** 验证 AbortSignal 与带认证的幂等取消入口。 */
test('shouldPassAbortSignalAndUseAuthenticatedIdempotentCancelEndpoint', async () => {
  const controller = new AbortController()

  /** 提供本用例指定的流响应，隔离真实网络与模型调用。 */
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.signal, controller.signal)
    throw new DOMException('aborted', 'AbortError')
  }
  await assert.rejects(
    /** 仅验证失败协议，空回调有意不改变测试状态。 */
    streamChat(
      { conversationId: 1, knowledgeBaseIds: [1], message: '问' },
      controller.signal,
      () => {
      },
      () => {
      },
    ),
    { name: 'AbortError' },
  )

  /** 检查取消请求的转义路径和认证 Header，模拟幂等成功响应。 */
  http.defaults.adapter = async config => {
    assert.equal(config.url, '/chat/r%3A1/cancel')
    assert.equal(config.headers.get('satoken'), 'chat-test-token')
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, data: null } }
  }
  await cancelChat('r:1')
})

/** 验证安全渲染 Markdown 表格、代码、列表及白名单引用。 */
test('shouldRenderMarkdownTablesCodeListsAndOnlyWhitelistedCitationMarkersSafely', () => {
  const html = renderMarkdown(
    '<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n![remote](https://example.com/x)\n\n[C1] [C99]\n\n|头|值|\n|---|---|\n|a|b|\n\n- 项目\n\n```js\n<script>\n```',
    ['C1'],
  )
  assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('href="javascript:'))
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('<table>'))
  assert.ok(html.includes('<ul>'))
  assert.ok(html.includes('<pre'))
  assert.ok(html.includes('data-citation="C1"'))
  assert.ok(!html.includes('data-citation="C99"'))
})

/** 验证结构化表格引用转义来源且表头不重复。 */
test('shouldRenderStructuredCitationTablesAndEscapeSourceContentWithoutRepeatingHeaderRow', async () => {
  const citation = {
    snippet: '第 1 行：内部检索表示',
    tables: [
      {
        sheetName: null,
        tableIndex: 1,
        columns: [
          { key: 'A', label: '城市' },
          { key: 'B', label: '住宿上限' },
        ],
        rows: [
          { rowNumber: 2, cells: ['北京', '600'] },
          { rowNumber: 3, cells: ['<script>alert(1)</script>', ''] },
        ],
        truncated: true,
      },
    ],
  }
  const html = await renderToString(createSSRApp(CitationContent, { citation }))
  assert.ok(html.includes('<table'))
  assert.ok(html.includes('<thead'))
  assert.ok(html.includes('<tbody'))
  assert.ok(html.includes('北京'))
  assert.ok(html.includes('600'))
  assert.ok(html.includes('（空）'))
  assert.ok(html.includes('完整行'))
  assert.ok(html.includes('role="region"'))
  assert.ok(html.includes('tabindex="0"'))
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('内部检索表示'))
  assert.equal(html.match(/住宿上限/g)?.length, 1)
})

/** 验证同一 Excel 物理行的两组指标均展示。 */
test('shouldRenderBothMetricsWhenTheyShareOnePhysicalExcelRow', async () => {
  const citation = {
    snippet: '第 20 行：内部检索表示',
    tables: [
      {
        sheetName: '资产台账',
        tableIndex: 3,
        columns: [
          { key: 'A', label: '指标' },
          { key: 'B', label: '数值' },
        ],
        rows: [
          { rowNumber: 20, cells: ['资产数量', '12'] },
          { rowNumber: 20, cells: ['原值合计 元', '562400'] },
        ],
        truncated: false,
      },
    ],
  }
  const html = await renderToString(createSSRApp(CitationContent, { citation }))
  assert.ok(html.includes('<table'))
  assert.ok(!html.includes('<pre'))
  assert.equal(html.match(/<tr/g)?.length, 3)
  assert.ok(html.includes('资产数量'))
  assert.ok(html.includes('12'))
  assert.ok(html.includes('原值合计 元'))
  assert.ok(html.includes('562400'))
  assert.ok(!html.includes('内部检索表示'))
  assert.ok(!html.includes('完整行'))
})

/** 验证纯文本和旧引用预览仍可读。 */
test('shouldKeepPlainAndLegacyCitationPreviewsReadable', async () => {
  for (const citation of [{ snippet: '普通制度\n五天审批' }, { snippet: '<img src=x onerror=alert(1)>', tables: [] }]) {
    const html = await renderToString(createSSRApp(CitationContent, { citation }))
    assert.ok(html.includes('<pre'))
    assert.ok(!html.includes('<table'))
    assert.ok(!html.includes('<img'))
  }
})

/** 原文与 PDF 定位使用同一历史消息引用路径，所有请求继续携带认证 Header。 */
test('shouldRequestAuthorizedHistoricalSourceAndPdfByMessageCitation', async () => {
  const paths = []
  /** 记录原文定位路径，模拟后端固定历史版本的引用响应。 */
  http.defaults.adapter = async config => {
    paths.push(config.url)
    assert.equal(config.headers.get('satoken'), 'chat-test-token')
    return {
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: { code: 0, data: { format: 'PDF', fullEvidence: true } },
    }
  }
  await getCitationSource(7, 19, 'C1')
  await getCitationPdf(7, 19, 'C1')
  assert.deepEqual(paths, [
    '/conversations/7/messages/19/citations/C1/source',
    '/conversations/7/messages/19/citations/C1/pdf',
  ])
})

/** 检索诊断保留用户选择的复杂度和知识库范围，不在前端扩大查询范围。 */
test('shouldSendExplicitScopeAndComplexityToAdministratorDebugApi', async () => {
  /** 验证诊断请求参数与模型超时兼容的 HTTP 预算。 */
  http.defaults.adapter = async config => {
    assert.equal(config.url, '/admin/retrieval/debug')
    assert.equal(config.timeout, 30000)
    assert.deepEqual(JSON.parse(config.data), { query: '第3.2条', knowledgeBaseIds: [9], complexity: 'COMPLEX' })
    return {
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: { code: 0, data: { retrieval: { rerankStatus: 'FALLBACK_RRF' } } },
    }
  }
  const result = await debugRetrieval('第3.2条', [9], 'COMPLEX')
  assert.equal(result.retrieval.rerankStatus, 'FALLBACK_RRF')
})
