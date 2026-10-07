import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { init, use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GraphicComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'

use([BarChart, LineChart, PieChart, GraphicComponent, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])

const vite = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
const {
  usagePeriodEnding,
  dailyUsagePoints,
  callTrendOption,
  costTrendOption,
  modelDistributionOption,
  stageBreakdownOption,
} = await vite.ssrLoadModule('/src/utils/usageCharts.ts')

/** 本组只使用无 DOM 的 SVG 绘图和纯数据转换，结束后关闭测试 Vite。 */
after(async () => {
  await vite.close()
})

/** 构造账本分组，成本 NULL 与已知零成本必须保持可区分。 */
function group(label, calls, estimatedCost, unpricedCalls = 0) {
  return { label, calls, estimated_cost: estimatedCost, unpriced_calls: unpricedCalls, errors: 0, p95_ms: 100 }
}

/** 构造跨月份的每日统计，允许后端省略没有任何调用的日期。 */
function dashboard(daily) {
  return { from: '2026-09-30', to: '2026-10-02', daily }
}

/** 快捷区间包含结束日，跨月、跨年和闰年都按日历计算。 */
test('shouldKeepInclusiveDateWindowsAcrossMonthsYearsAndLeapDays', () => {
  assert.deepEqual(usagePeriodEnding('2026-10-06', 7), { from: '2026-09-30', to: '2026-10-06' })
  assert.deepEqual(usagePeriodEnding('2026-01-01', 7), { from: '2025-12-26', to: '2026-01-01' })
  assert.deepEqual(usagePeriodEnding('2024-03-01', 2), { from: '2024-02-29', to: '2024-03-01' })
  assert.deepEqual(usagePeriodEnding('2026-10-06', 1), { from: '2026-10-06', to: '2026-10-06' })
})

/** 无调用日期补零，但有调用且未定价的成本仍为空，已知零费用也不能丢失。 */
test('shouldDistinguishMissingActivityUnknownCostsAndKnownZeroCosts', () => {
  const rows = [group('2026-10-02', 2, 0), group('2026-09-30', 3, null, 3)]
  const original = structuredClone(rows)
  const points = dailyUsagePoints(dashboard(rows))
  assert.deepEqual(
    points.map(point => point.label),
    ['2026-09-30', '2026-10-01', '2026-10-02'],
  )
  assert.deepEqual(
    points.map(point => point.calls),
    [3, 0, 2],
  )
  assert.deepEqual(
    points.map(point => point.estimatedCost),
    [null, 0, 0],
  )
  assert.deepEqual(
    points.map(point => point.unpricedCalls),
    [3, 0, 0],
  )
  assert.deepEqual(rows, original)
})

/** 部分已定价的成本照实展示，不用不可估算调用推断缺失费用。 */
test('shouldKeepPartialCostAndUnpricedCallsOnSeparateAxes', () => {
  const points = dailyUsagePoints(dashboard([group('2026-09-30', 4, 0.012345, 2), group('2026-10-02', 2, null, 2)]))
  const option = costTrendOption(points)
  assert.deepEqual(option.series[0].data, [0.012345, 0, null])
  assert.deepEqual(option.series[1].data, [2, 0, 2])
  assert.equal(option.series[1].yAxisIndex, 1)
  assert.equal(option.series[0].tooltip.valueFormatter(null), '不可估算')
  assert.equal(option.series[0].tooltip.valueFormatter(0), '¥ 0.000000')
  assert.equal(option.series[0].tooltip.valueFormatter(0.012345), '¥ 0.012345')
  assert.equal(option.yAxis[0].axisLabel.show, true)
})

/** 全部未定价时隐藏虚构的费用刻度，只保留未知调用量的真实坐标。 */
test('shouldHideCostScaleWhenEveryCallHasUnknownCost', () => {
  const points = dailyUsagePoints(dashboard([group('2026-10-02', 2, null, 2)]))
  const option = costTrendOption(points)
  assert.equal(option.yAxis[0].axisLabel.show, false)
  assert.equal(option.yAxis[0].name, '暂无已估算费用')
  assert.equal(option.yAxis[1].splitLine.show, true)
  assert.deepEqual(option.series[0].data, [0, 0, null])
})

/** 调用与错误共享同一日历，图表不把失败调用从总量中排除。 */
test('shouldPlotActualCallsAndErrorsWithoutChangingTheLedger', () => {
  const row = { ...group('2026-10-02', 4, 0.01), errors: 2 }
  const points = dailyUsagePoints(dashboard([row]))
  const option = callTrendOption(points)
  assert.deepEqual(option.series[0].data, [0, 0, 4])
  assert.deepEqual(option.series[1].data, [0, 0, 2])
  assert.equal(row.calls, 4)
  assert.equal(row.errors, 2)
})

/** 模型排序不改动表格，零调用分组不制造可见扇区，空数据也不放置样例。 */
test('shouldRankRealModelUsageWithoutMutatingRowsOrAddingPlaceholderCalls', () => {
  const rows = [group('model-a', 2, null), group('model-b', 7, 0.02), group('model-empty', 0, null)]
  const original = structuredClone(rows)
  const option = modelDistributionOption(rows)
  assert.deepEqual(option.series[0].data, [
    { name: 'model-b', value: 7 },
    { name: 'model-a', value: 2 },
  ])
  assert.equal(option.graphic[0].style.text, '9')
  assert.deepEqual(rows, original)
  assert.deepEqual(modelDistributionOption([]).series[0].data, [])
})

/** 阶段显示名与数值保持对应，未知阶段仍可在图表与账本中核对。 */
test('shouldKeepUnknownStageLabelsAndSortCountsWithTheirLabels', () => {
  const rows = [group('GENERATION', 2, 0.01), group('FUTURE_STAGE', 5, null)]
  const option = stageBreakdownOption(rows)
  assert.deepEqual(option.yAxis.data, ['FUTURE_STAGE', '回答生成'])
  assert.deepEqual(option.series[0].data, [5, 2])
  assert.deepEqual(
    rows.map(row => row.label),
    ['GENERATION', 'FUTURE_STAGE'],
  )
})

/** 使用真实 ECharts SVG 渲染四种配置，及早发现系列或组件注册遗漏。 */
test('shouldRenderEveryUsageChartWithTheInstalledEchartsSvgRenderer', () => {
  const rows = [group('2026-09-30', 4, null, 4), group('2026-10-02', 2, 0.012345)]
  const points = dailyUsagePoints(dashboard(rows))
  const options = [
    callTrendOption(points),
    costTrendOption(points),
    modelDistributionOption([group('model-one', 6, 0.012345)]),
    stageBreakdownOption([group('GENERATION', 6, 0.012345)]),
  ]
  for (const option of options) {
    const chart = init(undefined, undefined, { renderer: 'svg', ssr: true, width: 640, height: 320 })
    try {
      chart.setOption({ ...option, animation: false })
      const svg = chart.renderToSVGString()
      assert.ok(svg.startsWith('<svg'))
      assert.ok(svg.includes('<path'))
      assert.ok(!svg.includes('NaN'))
    } finally {
      chart.dispose()
    }
  }
})
