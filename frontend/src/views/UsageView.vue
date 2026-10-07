<script setup lang="ts">
import { PERCENT_SCALE, COST_DISPLAY_DECIMAL_PLACES } from '../constants/units'
import { priceBasis, redisStatus } from '../constants/contracts'
import { USAGE_DEFAULT_DAYS, USAGE_TIME_ZONE, usageDatePresets } from '../constants/usageCharts'
import { ref, computed, onBeforeUnmount, onMounted, watch } from 'vue'
import UsageCharts from '../components/UsageCharts.vue'
import { getUsage, rebuildUsage, listPrices, createPrice } from '../api/usage'
import { ApiRequestError } from '../api/http'
import { usagePeriodEnding } from '../utils/usageCharts'
import type { UsageDashboard, UsagePrice, PriceRequest } from '../types/usage'

const props = defineProps<{ management?: boolean }>()
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: USAGE_TIME_ZONE }).format(new Date())
const initialPeriod = usagePeriodEnding(today, USAGE_DEFAULT_DAYS)
const from = ref(initialPeriod.from)
const to = ref(today)
const dashboard = ref<UsageDashboard | null>(null)
const prices = ref<UsagePrice[]>([])
const busy = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const price = ref<PriceRequest>({ model: '', version: '', basis: priceBasis.tokens, inputPrice: 0, outputPrice: 0 })
let loadRevision = 0
let alive = true

/** 快捷范围与手填日期共用同一查询，不改变本人或管理员的权限范围。 */
const periods = computed(() =>
  usageDatePresets.map(preset => ({ ...preset, ...usagePeriodEnding(today, preset.days) })),
)

/** 错误率按实际模型调用数计算，零调用时显示为零。 */
const errorRate = computed(() => {
  const totals = dashboard.value?.totals
  return totals?.calls ? `${((totals.errors / totals.calls) * PERCENT_SCALE).toFixed(1)}%` : '0%'
})

/** 同一格式展示管理员或本人统计，表格提供可核对的精确数值。 */
const groups = computed(() => [
  { title: '按阶段', rows: dashboard.value?.stages ?? [] },
  { title: '按模型', rows: dashboard.value?.models ?? [] },
  { title: '每日趋势', rows: dashboard.value?.daily ?? [] },
])

/** 两个入口复用同一页面实例时清除旧身份口径，重新请求当前入口的数据。 */
watch(() => props.management, reloadScope)

/** 首次进入时读取真实统计，页面卸载无需保留后台轮询。 */
onMounted(load)

/** 离开页面使尚未完成的查询失效，避免迟到响应恢复旧图表。 */
onBeforeUnmount(() => {
  alive = false
  loadRevision++
})

/** 刷新日期区间内历史账本，管理员同时核对价格版本。 */
async function load() {
  const revision = ++loadRevision
  const management = Boolean(props.management)
  busy.value = true
  error.value = null
  try {
    const usageDashboard = await getUsage(from.value, to.value, management)
    if (!alive || revision !== loadRevision) {
      return
    }
    dashboard.value = usageDashboard
    if (management) {
      const priceVersions = await listPrices()
      if (alive && revision === loadRevision) {
        prices.value = priceVersions
      }
    }
  } catch (cause) {
    if (alive && revision === loadRevision) {
      error.value = cause instanceof ApiRequestError ? cause.message : '读取用量失败，请重试。'
    }
  } finally {
    if (alive && revision === loadRevision) {
      busy.value = false
    }
  }
}

/** 当前入口改变时立即清除旧统计，管理员总量不能停留在本人页面。 */
function reloadScope(): void {
  dashboard.value = null
  prices.value = []
  notice.value = null
  void load()
}

/** NULL 成本保持未知，已估算部分使用固定币种展示。 */
function money(value: number | null): string {
  return value === null ? '不可估算' : `¥ ${Number(value).toFixed(COST_DISPLAY_DECIMAL_PLACES)}`
}

/** 重建实时统计不清空 Redis 其他业务数据，历史来源始终为数据库。 */
async function rebuild() {
  busy.value = true
  error.value = null
  try {
    const result = await rebuildUsage()
    notice.value =
      result.redisStatus === redisStatus.rebuilt
        ? '今日 Redis 统计已从数据库重建。'
        : 'Redis 暂不可用，历史账本可正常查询。'
  } catch (cause) {
    error.value = cause instanceof ApiRequestError ? cause.message : '重建失败，请重试。'
  } finally {
    busy.value = false
  }
}

/** 保存用户填写的新价格版本后刷新列表，不追溯修改历史成本。 */
async function savePrice() {
  busy.value = true
  error.value = null
  try {
    await createPrice(price.value)
    prices.value = await listPrices()
    notice.value = '价格版本已保存，仅对后续调用生效。'
  } catch (cause) {
    error.value = cause instanceof ApiRequestError ? cause.message : '保存价格失败，请检查版本是否重复。'
  } finally {
    busy.value = false
  }
}

/** 表单查询不重复提交正在读取的区间，仍保留浏览器原生日期校验。 */
function query(): void {
  if (!busy.value) {
    void load()
  }
}

/** 快捷日期直接刷新图表和账本，查询期间阻止连续切换。 */
async function selectPeriod(period: { from: string; to: string }): Promise<void> {
  if (busy.value) {
    return
  }
  from.value = period.from
  to.value = period.to
  await load()
}
</script>

<template>
  <main class="page usage-page">
    <header class="page-header">
      <div
        ><p class="eyebrow">USAGE & INSIGHTS</p><h1>{{ management ? 'AI 用量与成本管理' : '我的 AI 用量' }}</h1>
        <p class="subtitle">了解每一次调用，让知识工作的投入清晰可见。</p></div
      >
    </header>
    <p class="hint">按北京时间统计。成本为已配置价格的估算；未计量或未定价调用不算作零成本。</p>
    <form
      class="usage-controls"
      @submit.prevent="query"
    >
      <div
        class="usage-periods"
        role="group"
        aria-label="快捷日期区间"
      >
        <button
          v-for="period in periods"
          :key="period.label"
          type="button"
          :class="{ 'is-selected': from === period.from && to === period.to }"
          :aria-pressed="from === period.from && to === period.to"
          :disabled="busy"
          @click="selectPeriod(period)"
          >{{ period.label }}</button
        >
      </div>
      <label
        >开始日期
        <input
          v-model="from"
          type="date"
          :max="to"
          :disabled="busy"
          required
      /></label>
      <label
        >结束日期
        <input
          v-model="to"
          type="date"
          :min="from"
          :disabled="busy"
          required
      /></label>
      <button :disabled="busy">{{ busy ? '读取中…' : '查询' }}</button>
      <button
        v-if="management"
        type="button"
        class="secondary"
        :disabled="busy"
        @click="rebuild"
        >重建今日实时统计</button
      >
    </form>
    <p
      v-if="error"
      class="notice error"
      role="alert"
      >{{ error }}</p
    >
    <p
      v-if="notice"
      class="notice"
      role="status"
      >{{ notice }}</p
    >
    <div
      v-if="dashboard"
      :aria-busy="busy"
    >
      <div class="usage-metrics">
        <article
          ><strong>{{ dashboard.totals.calls }}</strong
          ><span>模型调用</span></article
        >
        <article
          ><strong>{{ Number(dashboard.totals.input_tokens) + Number(dashboard.totals.output_tokens) }}</strong
          ><span>已报告 Tokens</span></article
        >
        <article
          ><strong>{{ money(dashboard.totals.estimated_cost) }}</strong
          ><span>已估算部分</span></article
        >
        <article
          ><strong>{{ dashboard.totals.unpriced_calls }}</strong
          ><span>不可完整估算调用</span></article
        >
        <article
          ><strong>{{ Math.round(dashboard.totals.p95_ms) }} ms</strong><span>调用 P95</span></article
        >
        <article
          ><strong>{{ errorRate }}</strong
          ><span>调用错误率</span></article
        >
      </div>
      <UsageCharts :dashboard="dashboard" />
      <section
        v-for="group in groups"
        :key="group.title"
      >
        <h2>{{ group.title }}</h2>
        <div class="usage-table"
          ><table>
            <thead
              ><tr><th>分组</th><th>调用</th><th>估算成本</th><th>不可估算</th><th>错误</th><th>P95 ms</th></tr></thead
            >
            <tbody
              ><tr
                v-for="row in group.rows"
                :key="row.label"
                ><td>{{ row.label }}</td
                ><td>{{ row.calls }}</td
                ><td>{{ money(row.estimated_cost) }}</td
                ><td>{{ row.unpriced_calls }}</td
                ><td>{{ row.errors }}</td
                ><td>{{ Math.round(row.p95_ms) }}</td></tr
              ></tbody
            >
          </table></div
        >
        <p
          v-if="!group.rows.length"
          class="hint"
          >该区间暂无模型调用。</p
        >
      </section>
      <h2>检索路线分布</h2>
      <div class="usage-table"
        ><table>
          <thead
            ><tr
              ><th>路线</th><th>请求</th><th>失败</th><th>取消</th><th>进行中</th><th>请求 P95 ms</th
              ><th>首 Token P95 ms</th><th>缓存命中</th></tr
            ></thead
          >
          <tbody
            ><tr
              v-for="row in dashboard.routes"
              :key="row.label"
              ><td>{{ row.label }}</td
              ><td>{{ row.requests }}</td
              ><td>{{ row.errors }}</td
              ><td>{{ row.cancelled }}</td
              ><td>{{ row.running }}</td
              ><td>{{ row.p95_ms === null ? '未知' : Math.round(row.p95_ms) }}</td
              ><td>{{ row.first_token_p95_ms === null ? '未知' : Math.round(row.first_token_p95_ms) }}</td
              ><td>{{ row.cache_hits }}</td></tr
            ></tbody
          >
        </table></div
      >
    </div>
    <section v-if="management">
      <h2>估算价格版本</h2>
      <p class="hint"
        >填写已确认的 CNY 价格。TOKENS 为每百万输入/输出 Token；CALL 为每次调用，UNITS 为每个供应方报告单位。</p
      >
      <form
        class="usage-controls"
        @submit.prevent="savePrice"
      >
        <label
          >模型
          <input
            v-model="price.model"
            required
            maxlength="120"
        /></label>
        <label
          >版本
          <input
            v-model="price.version"
            required
            maxlength="80"
        /></label>
        <label
          >计价方式
          <select v-model="price.basis"
            ><option>TOKENS</option
            ><option>CALL</option
            ><option>UNITS</option></select
          ></label
        >
        <label
          >输入/调用/单位单价
          <input
            v-model.number="price.inputPrice"
            type="number"
            min="0"
            step="0.00000001"
            required
        /></label>
        <label
          >输出单价
          <input
            v-model.number="price.outputPrice"
            type="number"
            min="0"
            step="0.00000001"
            required
        /></label>
        <button :disabled="busy">保存新版本</button>
      </form>
      <div class="usage-table"
        ><table>
          <thead
            ><tr><th>模型</th><th>版本</th><th>方式</th><th>输入单价</th><th>输出单价</th><th>生效时间</th></tr></thead
          >
          <tbody
            ><tr
              v-for="row in prices"
              :key="`${row.model}:${row.version}`"
              ><td>{{ row.model }}</td
              ><td>{{ row.version }}</td
              ><td>{{ row.basis }}</td
              ><td>{{ row.input_price }}</td
              ><td>{{ row.output_price }}</td
              ><td>{{ row.effective_at }}</td></tr
            ></tbody
          >
        </table></div
      >
    </section>
  </main>
</template>

<style scoped>
.usage-page {
  max-width: 1400px;
}
.usage-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
  margin: 20px 0;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.usage-controls label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}
.usage-periods {
  display: flex;
  align-self: stretch;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border-radius: 9px;
  background: var(--surface-soft);
}
.usage-periods button {
  min-height: 36px;
  padding: 8px 12px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
}
.usage-periods button.is-selected {
  border-color: var(--border);
  color: var(--brand);
  background: var(--surface);
  box-shadow: 0 2px 5px #19382f07;
}
.usage-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}
.usage-metrics article {
  display: grid;
  gap: 8px;
  padding: 22px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
}
.usage-metrics strong {
  font-size: clamp(20px, 1.7vw, 26px);
  font-weight: 550;
  letter-spacing: -0.8px;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.usage-metrics article:nth-child(3) strong {
  font-size: 20px;
}
.usage-metrics span {
  font-size: 12px;
  color: var(--muted);
}
.usage-page section {
  margin-top: 30px;
}
.usage-page h2 {
  margin-top: 30px;
}
@media (max-width: 1200px) {
  .usage-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
.usage-table {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  text-align: left;
  padding: 16px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
</style>
