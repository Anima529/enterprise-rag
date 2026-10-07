/** 用量页面的快捷日期窗口，结束日期均包含北京时间的今天。 */
export const usageDatePresets = [
  { label: '今天', days: 1 },
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
] as const

export const USAGE_DEFAULT_DAYS = 7
export const USAGE_TIME_ZONE = 'Asia/Shanghai'
export const ISO_DATE_LENGTH = 10
export const ISO_MONTH_OFFSET = 5

/** 图表样式与企业工作台保持一致，几何尺寸集中维护。 */
export const usageChartTheme = {
  ink: '#263c35',
  muted: '#66766f',
  grid: '#e7ece4',
  green: '#2e6253',
  orange: '#bc704c',
  palette: ['#2e6253', '#bc704c', '#899c68', '#6b8998', '#b7a27f', '#91869b', '#5b8277', '#aa8b6c'],
  fontFamily: 'Segoe UI, Microsoft YaHei, sans-serif',
  fontSize: 12,
  legendGap: 18,
  legendSymbolSize: 9,
  modelLegendHeight: 48,
  lineWidth: 3,
  symbolSize: 6,
  areaOpacity: 0.09,
  barWidth: 28,
  barRadius: 4,
  stageLabelWidth: 86,
  stageAxisHeadroom: 1.2,
  centerFontSize: 32,
  centerFontWeight: 600,
  animationDuration: 300,
  gridLayout: { left: 8, right: 12, top: 54, bottom: 8, outerBoundsMode: 'same', outerBoundsContain: 'all' },
} as const

/** 将账本阶段显示为可读名称，未知阶段仍保留原值以便核对。 */
export const usageStageLabels: Record<string, string> = {
  REWRITE: '问题改写',
  ROUTE: '路线判断',
  DECOMPOSE: '问题拆解',
  HYDE: '假设文档',
  FAITHFULNESS: '证据校验',
  GENERATION: '回答生成',
  EMBEDDING: '向量化',
  RERANK: '结果重排',
}
