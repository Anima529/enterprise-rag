<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { createDataset, listDatasets, startEvaluation, listEvaluationRuns, getEvaluationRun } from '../api/evaluation'
import { ApiRequestError } from '../api/http'
import ResultDialog from '../components/ResultDialog.vue'
import {
  evaluationModes,
  evaluationStates,
  evaluationScoreDecimals,
  evaluationPollIntervalMs,
  evaluationCaseTemplate,
} from '../constants/evaluation'
import type {
  EvaluationCase,
  EvaluationDataset,
  EvaluationDetail,
  EvaluationMode,
  EvaluationRun,
} from '../types/evaluation'

const datasets = ref<EvaluationDataset[]>([])
const selectedDatasetId = ref<number | null>(null)
const runs = ref<EvaluationRun[]>([])
const details = ref<EvaluationDetail[]>([])
const selectedRunIds = ref<number[]>([])
const name = ref('')
const description = ref('')
const evaluationUserId = ref<number | null>(null)
const casesJson = ref(JSON.stringify(evaluationCaseTemplate, null, 2))
const runName = ref('')
const mode = ref<EvaluationMode>(evaluationModes.rerank)
const busy = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const dialogKind = ref<'run' | 'comparison'>('comparison')
const dialogRunIds = ref<number[]>([])
const dialogLoading = ref(false)
const dialogError = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let generation = 0
let dialogRevision = 0
let disposed = false

/** 数据集身份始终固定，切换页面选择不会修改已保存的实验。 */
const selectedDataset = computed(() => datasets.value.find(dataset => dataset.id === selectedDatasetId.value))

/** 区分新实验的实时结果与已选实验的详情对比。 */
const dialogTitle = computed(() => (dialogKind.value === 'run' ? '实验运行结果' : '实验详情与对比'))

/** 首次加载管理数据，失败保留可重试按钮。 */
onMounted(loadDatasets)

/** 卸载时终止轮询，并丢弃迟到响应，防止页面退出后继续更新。 */
onBeforeUnmount(() => {
  disposed = true
  generation++
  clearTimeout(pollTimer)
  closeResults()
})

/** 加载最近数据集并保持现有选择，首次进入时选择最新数据集。 */
async function loadDatasets() {
  busy.value = true
  error.value = null
  try {
    const loaded = await listDatasets()
    if (disposed) {
      return
    }
    datasets.value = loaded
    selectedDatasetId.value ??= loaded[0]?.id ?? null
    await loadRuns()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

/** 选择变化时作废旧轮询和对比选择，只有当前代次的响应可以更新页面。 */
async function changeDataset() {
  generation++
  clearTimeout(pollTimer)
  closeResults()
  selectedRunIds.value = []
  details.value = []
  runs.value = []
  await loadRuns()
}

/** 活动实验继续轮询，终态停止；失败显式显示并可手工刷新。 */
async function loadRuns() {
  const current = generation
  const datasetId = selectedDatasetId.value
  clearTimeout(pollTimer)
  if (datasetId === null) {
    return
  }
  try {
    const loaded = await listEvaluationRuns(datasetId)
    if (disposed || current !== generation || datasetId !== selectedDatasetId.value) {
      return
    }
    runs.value = loaded
    if (dialogOpen.value && dialogKind.value === 'run') {
      await readResults()
    }
    if (disposed || current !== generation || datasetId !== selectedDatasetId.value) {
      return
    }
    if (loaded.some(run => run.status === evaluationStates.pending || run.status === evaluationStates.running)) {
      pollTimer = setTimeout(loadRuns, evaluationPollIntervalMs)
    }
  } catch (cause) {
    if (current === generation && !disposed) {
      showError(cause)
      if (dialogOpen.value && dialogKind.value === 'run') {
        dialogError.value = error.value
      }
    }
  }
}

/** JSON 标注经服务端再次校验，预期 Chunk 必须来自当前版本的授权 Child。 */
async function saveDataset() {
  busy.value = true
  error.value = null
  try {
    const cases: unknown = JSON.parse(casesJson.value)
    if (!Array.isArray(cases) || evaluationUserId.value === null) {
      throw new Error('请填写执行用户 ID 和案例数组。')
    }
    selectedDatasetId.value = await createDataset(
      name.value,
      description.value,
      evaluationUserId.value,
      cases as EvaluationCase[],
    )
    await loadDatasets()
    await changeDataset()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

/** 立即打开运行窗口再提交异步实验，后台轮询在窗口中更新真实状态和逐题结果。 */
async function startRun() {
  if (selectedDatasetId.value === null) {
    return
  }
  busy.value = true
  error.value = null
  const current = generation
  const ticket = openResults('run')
  try {
    const runId = await startEvaluation(selectedDatasetId.value, runName.value, mode.value)
    if (disposed || current !== generation) {
      return
    }
    if (dialogOpen.value && ticket === dialogRevision) {
      dialogRunIds.value = [runId]
    }
    await loadRuns()
  } catch (cause) {
    if (!disposed && ticket === dialogRevision) {
      dialogError.value = failure(cause)
    }
  } finally {
    if (!disposed && current === generation) {
      busy.value = false
    }
    if (!disposed && ticket === dialogRevision) {
      dialogLoading.value = false
    }
  }
}

/** 为当前操作清空旧详情并打开窗口，返回用于拒绝迟到响应的代次。 */
function openResults(kind: 'run' | 'comparison'): number {
  dialogRevision++
  dialogKind.value = kind
  dialogRunIds.value = []
  details.value = []
  dialogError.value = null
  dialogLoading.value = true
  dialogOpen.value = true
  return dialogRevision
}

/** 关闭结果窗口并作废详情读取；已提交的实验继续在后台运行和列表中更新。 */
function closeResults(): void {
  dialogRevision++
  dialogOpen.value = false
  dialogRunIds.value = []
  details.value = []
  dialogError.value = null
  dialogLoading.value = false
}

/** 只接收当前数据集与窗口的详情，来源或配置不同的实验在窗口中明确提示不可归因。 */
async function readResults(): Promise<void> {
  if (!dialogOpen.value || !dialogRunIds.value.length) {
    return
  }
  const current = generation
  const ticket = dialogRevision
  dialogLoading.value = true
  try {
    const loaded = await Promise.all(dialogRunIds.value.map(getEvaluationRun))
    if (!disposed && current === generation && ticket === dialogRevision) {
      details.value = loaded
      dialogError.value = null
      const first = loaded[0]
      const second = loaded[1]
      if (first && second && comparisonSnapshot(first) !== comparisonSnapshot(second)) {
        dialogError.value = '两次实验的来源、权限或配置已变化。可查看追踪记录，但不能将分数差解释为检索模式的效果。'
      }
    }
  } catch (cause) {
    if (!disposed && current === generation && ticket === dialogRevision) {
      dialogError.value = failure(cause)
    }
  } finally {
    if (!disposed && current === generation && ticket === dialogRevision) {
      dialogLoading.value = false
    }
  }
}

/** 点击后立即在弹窗中读取所选的一至两个实验，不在页面底部追加详情。 */
async function compare(): Promise<void> {
  openResults('comparison')
  dialogRunIds.value = selectedRunIds.value.slice(0, 2)
  await readResults()
}

/** 对比来源、权限和配置，排除实验 ID 与时间，避免将不同文档版本的分数误当作算法差异。 */
function comparisonSnapshot(detail: EvaluationDetail): string {
  const snapshot = detail.config_snapshot
  return JSON.stringify([
    snapshot.configurationHash,
    snapshot.evaluationUserId,
    snapshot.scopeFingerprints,
    snapshot.documents,
  ])
}

/** 未知评分保留未知，不能转换为零或通过。 */
function score(value: number | null | undefined): string {
  return value == null ? '未知' : value.toFixed(evaluationScoreDecimals)
}

/** 快照作为纯文本显示，案例中的 HTML 与注入文本不会执行。 */
function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

/** 将异常转换为可读原因，窗口和页面使用各自的提示位置。 */
function failure(cause: unknown): string {
  return cause instanceof ApiRequestError || cause instanceof Error ? cause.message : '评估操作失败，请重试。'
}

/** 数据集和列表加载失败在页面显示，生成结果的失败在所属弹窗显示。 */
function showError(cause: unknown): void {
  error.value = failure(cause)
}
</script>

<template>
  <main class="page evaluation-page">
    <header class="page-header"
      ><div
        ><p class="eyebrow">QUALITY & EVALUATION</p><h1>离线评估与实验对比</h1>
        <p class="subtitle">用可复现的实验，持续检查回答质量。</p></div
      ></header
    >
    <p class="hint">数据集和执行身份固定。分数来自实际运行；未知 Judge、错误和权限负例单独统计。</p>
    <p
      v-if="error"
      class="notice error"
      role="alert"
      >{{ error }}</p
    >
    <details>
      <summary>创建不可变数据集</summary>
      <p class="hint"
        >先上传合成文档并完成索引。Child ID 可在文档分块预览或检索调试中读取。无答案题的 expectedChunkIds
        留空；权限负例设 expectDenied=true。history 填写固定多轮历史。</p
      >
      <form
        class="dataset-form"
        @submit.prevent="saveDataset"
      >
        <label
          >数据集名称<input
            v-model="name"
            required
            maxlength="160"
        /></label>
        <label
          >说明<input
            v-model="description"
            maxlength="4000"
        /></label>
        <label
          >执行用户 ID<input
            v-model.number="evaluationUserId"
            type="number"
            min="1"
            required
        /></label>
        <label
          >案例 JSON<textarea
            v-model="casesJson"
            rows="14"
            required
            spellcheck="false"
          />
        </label>
        <button :disabled="busy">保存数据集</button>
      </form>
    </details>
    <form
      class="evaluation-controls"
      @submit.prevent="startRun"
    >
      <label
        >数据集<select
          v-model="selectedDatasetId"
          aria-label="数据集"
          @change="changeDataset"
        >
          <option
            v-for="dataset in datasets"
            :key="dataset.id"
            :value="dataset.id"
            >{{ dataset.name }}（{{ dataset.case_count }} 题）</option
          >
        </select></label
      >
      <label
        >实验名称<input
          v-model="runName"
          required
          maxlength="160"
      /></label>
      <label
        >检索模式<select
          v-model="mode"
          aria-label="检索模式"
        >
          <option :value="evaluationModes.vector">纯向量</option>
          <option :value="evaluationModes.hybrid">Hybrid + RRF</option>
          <option :value="evaluationModes.rerank">Hybrid + RRF + Rerank</option>
        </select></label
      >
      <button :disabled="busy || !selectedDatasetId">启动实验</button>
      <button
        type="button"
        class="secondary"
        :disabled="busy"
        @click="loadDatasets"
        >刷新</button
      >
    </form>
    <p
      v-if="selectedDataset"
      class="hint"
      >执行用户：{{ selectedDataset.evaluation_user_id }} · {{ selectedDataset.description }}</p
    >
    <div class="evaluation-table"
      ><table>
        <thead
          ><tr
            ><th>对比</th><th>实验 / 模式</th><th>状态</th><th>HitRate@K</th><th>Recall@K</th><th>MRR@K</th
            ><th>相关题分母</th><th>Faithfulness</th><th>Relevance</th><th>错误 / 权限通过</th></tr
          ></thead
        >
        <tbody
          ><tr
            v-for="run in runs"
            :key="run.id"
          >
            <td
              ><input
                v-model="selectedRunIds"
                type="checkbox"
                :value="run.id"
                :aria-label="`对比 ${run.name}`"
                :disabled="!selectedRunIds.includes(run.id) && selectedRunIds.length >= 2"
            /></td>
            <td>{{ run.name }}<br />{{ run.mode }}</td
            ><td>{{ run.status }}<br />{{ run.error_code }}</td> <td>{{ score(run.summary?.hitRate) }}</td
            ><td>{{ score(run.summary?.recall) }}</td
            ><td>{{ score(run.summary?.mrr) }}</td>
            <td>{{ run.summary?.retrievalDenominator ?? '—' }} / K={{ run.summary?.metricK ?? '—' }}</td>
            <td>{{ score(run.summary?.faithfulness) }}（{{ run.summary?.faithfulnessDenominator ?? 0 }}）</td>
            <td>{{ score(run.summary?.answerRelevance) }}（{{ run.summary?.answerRelevanceDenominator ?? 0 }}）</td>
            <td
              >{{ run.summary?.errors ?? '—' }} / {{ run.summary?.permissionPassed ?? 0 }} /
              {{ run.summary?.permissionCases ?? 0 }}</td
            >
          </tr></tbody
        >
      </table></div
    >
    <p
      v-if="!runs.length"
      class="hint"
      >暂无实验。创建数据集后选择模式启动。</p
    >
    <button
      :disabled="busy || !selectedRunIds.length"
      @click="compare"
      >查看与对比所选实验</button
    >
    <ResultDialog
      :open="dialogOpen"
      :title="dialogTitle"
      :busy="dialogLoading"
      :error="dialogError"
      :loading-text="dialogKind === 'run' ? '正在提交实验或读取最新结果…' : '正在读取所选实验…'"
      @close="closeResults"
    >
      <p
        v-if="
          dialogKind === 'run' &&
          details.some(
            detail => detail.status === evaluationStates.pending || detail.status === evaluationStates.running,
          )
        "
        class="notice"
        role="status"
        >实验正在后台运行，结果会自动更新。关闭此窗口后，可从实验列表再次查看。</p
      >
      <div class="comparison">
        <section
          v-for="detail in details"
          :key="detail.id"
        >
          <h2>{{ detail.name }}</h2>
          <p>{{ detail.status }} · {{ detail.error_code }}</p>
          <p v-if="detail.summary">
            HitRate@K：{{ score(detail.summary.hitRate) }} · Recall@K：{{ score(detail.summary.recall) }} · MRR@K：{{
              score(detail.summary.mrr)
            }}
            · Faithfulness：{{ score(detail.summary.faithfulness) }} · Relevance：{{
              score(detail.summary.answerRelevance)
            }}
          </p>
          <details
            ><summary>配置、身份、文档与 Prompt 快照</summary><pre>{{ pretty(detail.config_snapshot) }}</pre>
          </details>
          <details
            v-for="result in detail.results"
            :key="result.case_id"
            ><summary>题目 {{ result.case_id }}：排序、答案、核验与 trace</summary
            ><pre>{{ pretty(result.snapshot) }}</pre>
          </details>
        </section>
      </div>
    </ResultDialog>
  </main>
</template>

<style scoped>
.evaluation-page {
  max-width: 1400px;
}
.dataset-form {
  display: grid;
  gap: 12px;
  margin: 20px 0;
}
label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
}
textarea {
  width: 100%;
  font-family: monospace;
}
.evaluation-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin: 20px 0;
  padding: 22px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.evaluation-controls label {
  min-width: 0;
  max-width: 100%;
  flex: 1 1 170px;
}
.evaluation-page > details {
  padding: 10px 22px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.evaluation-table {
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
}
.comparison {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
  gap: 20px;
  margin-top: 24px;
}
.comparison > section {
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
pre {
  max-height: 480px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--surface-soft);
  padding: 12px;
  border-radius: 8px;
  font-size: 12px;
}
details {
  margin: 16px 0;
}
summary {
  cursor: pointer;
}
</style>
