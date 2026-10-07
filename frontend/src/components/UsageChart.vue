<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import type { ECharts } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { AriaComponent, GraphicComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { usageChartTheme } from '../constants/usageCharts'
import type { UsageChartOption } from '../types/usageChart'

use([
  BarChart,
  LineChart,
  PieChart,
  AriaComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  SVGRenderer,
])

const props = defineProps<{ option: UsageChartOption; description: string }>()
const container = ref<HTMLDivElement | null>(null)
let chart: ECharts | undefined
let observer: ResizeObserver | undefined
let reducedMotion: MediaQueryList | undefined

/** 新查询替换整份图表，避免旧日期或旧系列留在当前统计中。 */
watch(() => props.option, renderChart, { deep: true })

/** 图表仅在有尺寸的浏览器容器中初始化，响应容器宽度和减弱动效偏好。 */
onMounted(() => {
  if (!container.value) {
    return
  }
  reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  chart = init(container.value, undefined, { renderer: 'svg' })
  observer = new ResizeObserver(resizeChart)
  observer.observe(container.value)
  reducedMotion.addEventListener('change', renderChart)
  renderChart()
})

/** 页面离开后释放 ECharts、尺寸观察器与媒体监听，避免重复进入累计实例。 */
onBeforeUnmount(() => {
  observer?.disconnect()
  reducedMotion?.removeEventListener('change', renderChart)
  chart?.dispose()
  chart = undefined
})

/** 使用文本说明与纹理辅助辨识，图表信息仍可从下方原始账本核对。 */
function renderChart(): void {
  chart?.setOption(
    {
      ...props.option,
      animation: !reducedMotion?.matches,
      animationDuration: usageChartTheme.animationDuration,
      animationDurationUpdate: usageChartTheme.animationDuration,
      aria: { enabled: true, label: { description: props.description }, decal: { show: true } },
    },
    { notMerge: true },
  )
}

/** 根据卡片实际尺寸重排 SVG，桌面窗口调整不会造成图表裁切。 */
function resizeChart(): void {
  chart?.resize()
}
</script>

<template>
  <div
    ref="container"
    class="usage-chart-canvas"
    role="img"
    :aria-label="description"
  />
</template>

<style scoped>
.usage-chart-canvas {
  width: 100%;
  height: 270px;
  min-width: 0;
}
</style>
