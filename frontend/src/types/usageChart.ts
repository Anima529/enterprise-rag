import type { ComposeOption } from 'echarts/core'
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts'
import type {
  AriaComponentOption,
  GraphicComponentOption,
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
} from 'echarts/components'

export type UsageChartOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | AriaComponentOption
  | GraphicComponentOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>

export interface UsageChartDay {
  label: string
  calls: number
  errors: number
  estimatedCost: number | null
  unpricedCalls: number
}
