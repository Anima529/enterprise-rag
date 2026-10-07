<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import AppIcon from './AppIcon.vue'
import UsageChartPlaceholder from './UsageChartPlaceholder.vue'
import {
  callTrendOption,
  costTrendOption,
  dailyUsagePoints,
  modelDistributionOption,
  stageBreakdownOption,
} from '../utils/usageCharts'
import type { UsageDashboard } from '../types/usage'

const props = defineProps<{ dashboard: UsageDashboard }>()
const UsageChart = defineAsyncComponent({
  loader: loadChartRenderer,
  loadingComponent: UsageChartPlaceholder,
  errorComponent: UsageChartPlaceholder,
  delay: 0,
})

/** 每日图表依据已成功查询的区间生成，日期输入草稿不会提前改变图表口径。 */
const daily = computed(() => dailyUsagePoints(props.dashboard))

/** 两种身份共用相同绘图口径，空区间显示说明而不绘制示例数据。 */
const panels = computed(() => [
  {
    id: 'calls',
    title: '模型调用趋势',
    description: '每天的调用量与错误数，帮助发现使用高峰。',
    option: callTrendOption(daily.value),
    empty: props.dashboard.totals.calls === 0,
    note: '按北京时间逐日统计，无调用日期记为 0。',
  },
  {
    id: 'models',
    title: '模型调用分布',
    description: '各模型占总调用的比例，悬停查看具体次数。',
    option: modelDistributionOption(props.dashboard.models),
    empty: props.dashboard.models.length === 0,
    note: '占比按调用次数计算，具体数值可悬停查看。',
  },
  {
    id: 'cost',
    title: '成本与估算覆盖',
    description: '已估算费用与不可完整估算的调用，分别对应左右坐标。',
    option: costTrendOption(daily.value),
    empty: props.dashboard.totals.calls === 0,
    note: '绿色柱形为已估算费用；橙线为不可完整估算调用，未知成本保留为空。',
  },
  {
    id: 'stages',
    title: '阶段调用对比',
    description: '查看调用主要发生在哪些处理阶段。',
    option: stageBreakdownOption(props.dashboard.stages),
    empty: props.dashboard.stages.length === 0,
    note: '一次知识问答可能包含多个阶段的模型调用。',
  },
])

/** 有真实调用需要绘图时才加载 ECharts，空区间和其他页面不下载图表运行代码。 */
function loadChartRenderer(): Promise<typeof import('./UsageChart.vue')> {
  return import('./UsageChart.vue')
}
</script>

<template>
  <section
    class="usage-insights"
    aria-labelledby="usage-insights-title"
  >
    <div class="usage-insights-heading">
      <div><p class="eyebrow">AT A GLANCE</p><h2 id="usage-insights-title">用量概览</h2></div>
      <p>{{ dashboard.from }} — {{ dashboard.to }}</p>
    </div>
    <div class="usage-chart-grid">
      <article
        v-for="panel in panels"
        :key="panel.id"
        class="usage-chart-panel"
        :data-chart="panel.id"
      >
        <header
          ><h3>{{ panel.title }}</h3
          ><p>{{ panel.description }}</p></header
        >
        <div
          v-if="panel.empty"
          class="usage-chart-empty"
        >
          <AppIcon name="chart" />
          <strong>该区间暂无模型调用</strong>
          <span>可以调整日期，查看其他时间段的记录。</span>
        </div>
        <UsageChart
          v-else
          :option="panel.option"
          :description="`${panel.title}，${dashboard.from} 至 ${dashboard.to}。${panel.description}`"
        />
        <p class="usage-chart-note">{{ panel.note }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.usage-insights-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.usage-insights-heading .eyebrow {
  font-size: 9px;
}
.usage-insights-heading h2 {
  margin: 6px 0 0;
  font-size: 20px;
}
.usage-insights-heading > p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.usage-chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 20px;
}
.usage-chart-panel {
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.usage-chart-panel h3 {
  margin: 0 0 7px;
  font-size: 16px;
}
.usage-chart-panel header > p {
  min-height: 40px;
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}
.usage-chart-note {
  margin: 16px 0 0;
  padding-top: 14px;
  border-top: 1px solid var(--border);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
.usage-chart-empty {
  display: flex;
  min-height: 270px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 10px;
  background: #f8faf5;
  text-align: center;
}
.usage-chart-empty .app-icon {
  width: 32px;
  height: 32px;
  margin-bottom: 8px;
  color: #8a9d76;
}
.usage-chart-empty strong {
  font-size: 14px;
  font-weight: 550;
}
.usage-chart-empty span {
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 1000px) {
  .usage-chart-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
