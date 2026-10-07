import { COST_DISPLAY_DECIMAL_PLACES } from '../constants/units'
import { ISO_DATE_LENGTH, ISO_MONTH_OFFSET, usageChartTheme as theme, usageStageLabels } from '../constants/usageCharts'
import type { UsageDashboard, UsageGroup } from '../types/usage'
import type { UsageChartDay, UsageChartOption } from '../types/usageChart'

const countFormat = new Intl.NumberFormat('zh-CN')
const compactCountFormat = new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 })
const currencyAxisFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: COST_DISPLAY_DECIMAL_PLACES })

/** 用 UTC 运算已确认的北京时间日期，避免浏览器本地时区影响快捷窗口。 */
export function usagePeriodEnding(to: string, days: number): { from: string; to: string } {
  const start = new Date(`${to}T00:00:00Z`)
  start.setUTCDate(start.getUTCDate() - days + 1)
  return { from: start.toISOString().slice(0, ISO_DATE_LENGTH), to }
}

/** 补齐区间中的无调用日期；已有调用的未知成本继续保留 NULL。 */
export function dailyUsagePoints(dashboard: UsageDashboard): UsageChartDay[] {
  const daily = new Map(dashboard.daily.map(row => [row.label, row]))
  const end = new Date(`${dashboard.to}T00:00:00Z`)
  const points: UsageChartDay[] = []
  for (const day = new Date(`${dashboard.from}T00:00:00Z`); day <= end; day.setUTCDate(day.getUTCDate() + 1)) {
    const label = day.toISOString().slice(0, ISO_DATE_LENGTH)
    const row = daily.get(label)
    points.push({
      label,
      calls: row?.calls ?? 0,
      errors: row?.errors ?? 0,
      estimatedCost: row ? row.estimated_cost : 0,
      unpricedCalls: row?.unpriced_calls ?? 0,
    })
  }
  return points
}

/** 坐标轴保留月日，完整日期继续显示在悬停提示和下方账本中。 */
function shortDate(label: string): string {
  return label.slice(ISO_MONTH_OFFSET).replace('-', '/')
}

/** 图表调用数使用千位分隔，不改变用于绘图的实际数值。 */
function countValue(value: unknown): string {
  return countFormat.format(Number(value))
}

/** 成本提示保留账本精度，缺少价格时不能转成零成本。 */
function costValue(value: unknown): string {
  return value === null || value === undefined ? '不可估算' : `¥ ${Number(value).toFixed(COST_DISPLAY_DECIMAL_PLACES)}`
}

/** 成本坐标轴按实际量级展示，避免小额费用被四舍五入为零。 */
function costAxisValue(value: number): string {
  return currencyAxisFormat.format(value)
}

/** 日期图表共用网格、图例和安全的富文本提示，不拼接 HTML。 */
function trendBase(points: UsageChartDay[], boundaryGap = false): UsageChartOption {
  return {
    color: [theme.green, theme.orange],
    textStyle: { color: theme.ink, fontFamily: theme.fontFamily, fontSize: theme.fontSize },
    grid: { ...theme.gridLayout },
    legend: {
      top: 0,
      right: 0,
      itemGap: theme.legendGap,
      itemWidth: theme.legendSymbolSize,
      itemHeight: theme.legendSymbolSize,
      textStyle: { color: theme.muted, fontSize: theme.fontSize },
    },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
    xAxis: {
      type: 'category',
      data: points.map(point => point.label),
      boundaryGap,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: theme.muted, hideOverlap: true, formatter: shortDate },
    },
    yAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      axisLabel: { color: theme.muted },
      splitLine: { lineStyle: { color: theme.grid, type: 'dashed' } },
    },
  }
}

/** 调用与错误共享计数坐标，逐日真实数值不使用平滑插值。 */
export function callTrendOption(points: UsageChartDay[]): UsageChartOption {
  return {
    ...trendBase(points),
    series: [
      {
        name: '模型调用',
        type: 'line',
        data: points.map(point => point.calls),
        symbolSize: theme.symbolSize,
        lineStyle: { width: theme.lineWidth },
        areaStyle: { opacity: theme.areaOpacity },
        tooltip: { valueFormatter: countValue },
      },
      {
        name: '调用错误',
        type: 'line',
        data: points.map(point => point.errors),
        symbolSize: theme.symbolSize,
        lineStyle: { type: 'dashed' },
        tooltip: { valueFormatter: countValue },
      },
    ],
  }
}

/** 柱形仅表达已估算费用，右轴折线提示尚不可完整估算的调用数。 */
export function costTrendOption(points: UsageChartDay[]): UsageChartOption {
  const base = trendBase(points, true)
  const hasEstimatedCost = points.some(point => point.calls > 0 && point.estimatedCost !== null)
  return {
    ...base,
    yAxis: [
      {
        type: 'value',
        name: hasEstimatedCost ? '已估算（元）' : '暂无已估算费用',
        min: 0,
        nameTextStyle: { color: theme.muted },
        axisLabel: { show: hasEstimatedCost, color: theme.muted, formatter: costAxisValue },
        splitLine: { show: hasEstimatedCost, lineStyle: { color: theme.grid, type: 'dashed' } },
      },
      {
        type: 'value',
        name: '调用（次）',
        min: 0,
        minInterval: 1,
        nameTextStyle: { color: theme.muted },
        axisLabel: { color: theme.muted },
        splitLine: { show: !hasEstimatedCost, lineStyle: { color: theme.grid, type: 'dashed' } },
      },
    ],
    series: [
      {
        name: '已估算成本',
        type: 'bar',
        data: points.map(point => point.estimatedCost),
        barMaxWidth: theme.barWidth,
        itemStyle: { borderRadius: [theme.barRadius, theme.barRadius, 0, 0] },
        tooltip: { valueFormatter: costValue },
      },
      {
        name: '不可完整估算调用',
        type: 'line',
        yAxisIndex: 1,
        data: points.map(point => point.unpricedCalls),
        symbolSize: theme.symbolSize,
        lineStyle: { type: 'dashed' },
        tooltip: { valueFormatter: countValue },
      },
    ],
  }
}

/** 排序使用数组副本，避免图表改动后端返回的分组表格顺序。 */
function byCalls(rows: UsageGroup[]): UsageGroup[] {
  return rows.slice().sort((left, right) => right.calls - left.calls || left.label.localeCompare(right.label))
}

/** 环形图显示模型调用占比，保持全模型口径，中心总数只来自实际分组。 */
export function modelDistributionOption(rows: UsageGroup[]): UsageChartOption {
  const models = byCalls(rows).filter(row => row.calls > 0)
  const total = models.reduce((sum, row) => sum + row.calls, 0)
  return {
    color: [...theme.palette],
    textStyle: { fontFamily: theme.fontFamily, fontSize: theme.fontSize },
    tooltip: { trigger: 'item', renderMode: 'richText', confine: true, formatter: '{b}\n{c} 次 · {d}%' },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      bottom: 0,
      left: 'center',
      height: theme.modelLegendHeight,
      selectedMode: false,
      textStyle: { color: theme.muted },
    },
    graphic: [
      {
        type: 'text',
        left: 'center',
        top: '36%',
        silent: true,
        style: {
          text: compactCountFormat.format(total),
          fill: theme.ink,
          fontSize: theme.centerFontSize,
          fontWeight: theme.centerFontWeight,
        },
      },
      {
        type: 'text',
        left: 'center',
        top: '51%',
        silent: true,
        style: { text: '模型调用', fill: theme.muted, fontSize: theme.fontSize },
      },
    ],
    series: [
      {
        name: '模型调用',
        type: 'pie',
        radius: ['57%', '75%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderColor: '#ffffff', borderWidth: 2, borderRadius: theme.barRadius },
        data: models.map(row => ({ name: row.label, value: row.calls })),
      },
    ],
  }
}

/** 横向柱形按调用量比较各阶段，阶段语义只用于展示而不改变账本。 */
export function stageBreakdownOption(rows: UsageGroup[]): UsageChartOption {
  const stages = byCalls(rows)
  return {
    color: [theme.green],
    textStyle: { fontFamily: theme.fontFamily, fontSize: theme.fontSize },
    grid: { ...theme.gridLayout, top: theme.legendGap },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, valueFormatter: countValue },
    xAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      max: stageAxisMaximum,
      axisLabel: { color: theme.muted },
      splitLine: { lineStyle: { color: theme.grid, type: 'dashed' } },
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: stages.map(row => usageStageLabels[row.label] ?? row.label),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: theme.ink, width: theme.stageLabelWidth, overflow: 'truncate' },
    },
    series: [
      {
        name: '模型调用',
        type: 'bar',
        data: stages.map(row => row.calls),
        barMaxWidth: theme.barWidth,
        itemStyle: { borderRadius: [0, theme.barRadius, theme.barRadius, 0] },
        label: { show: true, position: 'right', color: theme.ink, formatter: '{c}' },
      },
    ],
  }
}

/** 为柱尾调用数预留空间，最大值恰好等于刻度时也不会裁切标签。 */
function stageAxisMaximum(extent: { max: number }): number {
  return Math.ceil(extent.max * theme.stageAxisHeadroom) || 1
}
