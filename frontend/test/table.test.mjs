import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const { default: SourcePreview } = await server.ssrLoadModule('/src/components/SourcePreview.vue')
const { default: CitationContent } = await server.ssrLoadModule('/src/components/CitationContent.vue')

/** 关闭本组用例创建的 Vite 测试服务。 */
after(async () => server.close())
const grid = {
  type: 'TABLE',
  text: null,
  truncated: false,
  table: {
    sheetName: '原工作表',
    tableIndex: 1,
    columns: [
      { key: 'A', label: '字段' },
      { key: 'B', label: '类型' },
    ],
    rows: [{ rowNumber: 7, cells: ['ip_address', '<script>untrusted</script>'] }],
    truncated: false,
  },
}

/** 构造混合来源中的普通正文预览块。 */
const prose = text => ({ type: 'TEXT', text, table: null, truncated: false })

/** 服务端渲染来源组件，供安全 HTML 和顺序断言。 */
const render = props => renderToString(createSSRApp(SourcePreview, props))

/** 验证详情与引用使用相同的有序预览渲染器。 */
test('shouldUseExactlyTheSameOrderedRendererForDetailsAndCitations', async () => {
  const preview = [prose('开头正文'), grid, prose('表后说明')]
  const details = await render({ preview, text: '内部检索文字' })
  const citation = await renderToString(
    createSSRApp(CitationContent, { citation: { preview, tables: [], snippet: '内部检索文字' } }),
  )
  assert.equal(citation, details)
  assert.ok(details.indexOf('开头正文') < details.indexOf('<table'))
  assert.ok(details.indexOf('<table') < details.indexOf('表后说明'))
  assert.ok(!details.includes('内部检索文字'))
  assert.ok(!details.includes('<script>'))
  assert.ok(details.includes('&lt;script&gt;'))
})

/** 验证表格片段前后的完整表格保留。 */
test('shouldKeepCompleteTablesBeforeAndAfterATableFragment', async () => {
  const html = await render({ preview: [grid, { ...prose('仅有局部原文'), truncated: true }, grid], text: '' })
  assert.equal(html.match(/<table/g)?.length, 2)
  assert.ok(html.includes('仅有局部原文'))
  assert.ok(html.includes('局部原文，完整行'))
})

/** 验证只有表头的表格和普通工作表说明保留。 */
test('shouldPreserveAHeaderOnlyTableAndPlainWorksheetNote', async () => {
  const html = await render({
    preview: [{ ...grid, table: { ...grid.table, rows: [] } }, prose('按指定基准核对截止日期。')],
    text: '',
  })
  assert.ok(html.includes('<thead'))
  assert.ok(html.includes('字段'))
  assert.ok(html.includes('按指定基准核对截止日期。'))
})
