<script setup lang="ts">
import { retrievalComplexity } from '../constants/contracts'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { listKnowledgeBases } from '../api/knowledgeBase'
import { debugRetrieval, getRetrievalTrace } from '../api/retrieval'
import { ApiRequestError } from '../api/http'
import CitationContent from '../components/CitationContent.vue'
import ResultDialog from '../components/ResultDialog.vue'
import RetrievalTraceContent from '../components/RetrievalTraceContent.vue'
import type { KnowledgeBase } from '../types/knowledgeBase'
import type { Complexity, RetrievalDebug, AdaptiveTrace } from '../types/retrieval'

const knowledgeBases = ref<KnowledgeBase[]>([])
const selected = ref<number[]>([])
const query = ref('')
const complexity = ref<Complexity>(retrievalComplexity.standard)
const result = ref<RetrievalDebug | null>(null)
const busy = ref(false)
const error = ref('')
const route = useRoute()
const requestId = ref(typeof route.query.requestId === 'string' ? route.query.requestId : '')
const trace = ref<AdaptiveTrace | null>(null)
const dialogOpen = ref(false)
const dialogKind = ref<'diagnostic' | 'trace'>('diagnostic')
const dialogError = ref('')
let revision = 0
let alive = true

/** 按当前操作区分检索诊断和已完成聊天的路线追踪。 */
const dialogTitle = computed(() => (dialogKind.value === 'diagnostic' ? '检索诊断结果' : '自动路线追踪'))

/** 将融合顺序与精排顺序关联，供管理员对照每个 Chunk 的排名变化。 */
const rows = computed(
  () =>
    result.value?.retrieval.fused.map(candidate => ({
      ...candidate,
      finalRank: result.value!.retrieval.ranked.findIndex(row => row.hit.chunkId === candidate.hit.chunkId) + 1,
      rerankScore: result.value!.retrieval.ranked.find(row => row.hit.chunkId === candidate.hit.chunkId)?.rerankScore,
    })) ?? [],
)

/** 分页加载当前管理员可用的知识库，调试查询必须显式选择范围。 */
onMounted(async () => {
  try {
    if (requestId.value) {
      await inspectTrace()
    }
    for (let page = 1; ; page++) {
      const response = await listKnowledgeBases(page)
      if (!alive) {
        return
      }
      knowledgeBases.value.push(...response.records)
      if (knowledgeBases.value.length >= response.total) {
        break
      }
    }
  } catch (cause) {
    if (alive) {
      error.value = cause instanceof ApiRequestError ? cause.message : '知识库加载失败'
    }
  }
})

/** 离开页面后使未完成的诊断和追踪读取失效。 */
onBeforeUnmount(() => {
  alive = false
  closeResults()
})

/** 清空上次结果并立即打开弹窗，加载和失败提示均在当前操作窗口中显示。 */
function openResults(kind: 'diagnostic' | 'trace'): number {
  revision++
  dialogKind.value = kind
  result.value = null
  trace.value = null
  dialogError.value = ''
  busy.value = true
  dialogOpen.value = true
  return revision
}

/** 关闭弹窗并作废当前读取，迟到响应不得恢复结果或重新打开窗口。 */
function closeResults(): void {
  revision++
  dialogOpen.value = false
  busy.value = false
  dialogError.value = ''
  result.value = null
  trace.value = null
}

/** 在弹窗中请求并展示诊断；只接收当前操作仍有效的召回和证据结果。 */
async function inspect() {
  const currentRevision = openResults('diagnostic')
  try {
    const diagnostic = await debugRetrieval(query.value, selected.value, complexity.value)
    if (alive && currentRevision === revision) {
      result.value = diagnostic
    }
  } catch (cause) {
    if (alive && currentRevision === revision) {
      dialogError.value = cause instanceof ApiRequestError ? cause.message : '检索诊断失败'
    }
  } finally {
    if (alive && currentRevision === revision) {
      busy.value = false
    }
  }
}

/** 读取已完成聊天的持久追踪，不重新调用模型或复用过期权限。 */
async function inspectTrace() {
  const currentRevision = openResults('trace')
  try {
    const requestTrace = await getRetrievalTrace(requestId.value.trim())
    if (alive && currentRevision === revision) {
      trace.value = requestTrace
    }
  } catch (cause) {
    if (alive && currentRevision === revision) {
      dialogError.value = cause instanceof ApiRequestError ? cause.message : '追踪读取失败'
    }
  } finally {
    if (alive && currentRevision === revision) {
      busy.value = false
    }
  }
}
</script>
<template>
  <main class="page debug-page">
    <header class="page-header"
      ><div
        ><p class="eyebrow">RETRIEVAL DIAGNOSTICS</p><h1>检索调试</h1>
        <p class="subtitle">从召回到证据，检查每一步检索结果。</p></div
      ></header
    >
    <p class="hint">对照授权候选、RRF 与 Cross-Encoder 排名，检查实际入模 Parent。</p>
    <form @submit.prevent="inspect">
      <fieldset :disabled="busy">
        <legend>知识库范围</legend>
        <label
          v-for="kb in knowledgeBases"
          :key="kb.id"
        >
          <input
            v-model="selected"
            type="checkbox"
            :value="kb.id"
          />{{ kb.name }}
        </label>
      </fieldset>
      <label
        >复杂度预算
        <select
          v-model="complexity"
          :disabled="busy"
        >
          <option>SIMPLE</option
          ><option>STANDARD</option
          ><option>COMPLEX</option>
        </select>
      </label>
      <p class="hint">此处检查指定路线的候选预算；聊天自动执行 Rewrite、Router、拆解和 HyDE。</p>
      <label
        >查询<textarea
          v-model="query"
          maxlength="4000"
          :disabled="busy"
          rows="3"
        />
      </label>
      <button :disabled="busy || !selected.length || !query.trim()">{{ busy ? '检索中…' : '运行诊断' }}</button>
    </form>
    <h2>自动路线追踪</h2>
    <form @submit.prevent="inspectTrace">
      <label
        >本人聊天 requestId
        <input
          v-model="requestId"
          maxlength="120"
          required
      /></label>
      <button :disabled="busy">读取追踪</button>
    </form>
    <p
      v-if="error"
      class="notice error"
      role="alert"
      >{{ error }}</p
    >
    <ResultDialog
      :open="dialogOpen"
      :title="dialogTitle"
      :busy="busy"
      :error="dialogError"
      :loading-text="dialogKind === 'diagnostic' ? '正在检索并准备诊断结果…' : '正在读取本次聊天的检索追踪…'"
      @close="closeResults"
    >
      <RetrievalTraceContent :trace="trace" />
      <template v-if="result">
        <p role="status">
          Vector：{{ result.retrieval.vectorStatus }} / {{ result.retrieval.vectorMs }}ms · FTS：{{
            result.retrieval.keywordStatus
          }}
          / {{ result.retrieval.keywordMs }}ms · Reranker：{{ result.retrieval.rerankStatus }} /
          {{ result.retrieval.rerankMs }}ms
        </p>
        <div
          class="debug-table"
          role="region"
          aria-label="候选排名"
          tabindex="0"
        >
          <table>
            <thead
              ><tr
                ><th>文档 / Chunk / Parent</th><th>Vector rank / score</th><th>FTS rank / score</th> <th>RRF</th
                ><th>精排 rank / score</th></tr
              ></thead
            >
            <tbody
              ><tr
                v-for="row in rows"
                :key="row.hit.chunkId"
              >
                <td
                  >{{ row.hit.documentName }} · v{{ row.hit.documentVersion }}<br />{{ row.hit.chunkId }} /
                  {{ row.hit.parentChunkId }}</td
                >
                <td>{{ row.vectorRank ?? '—' }} / {{ row.vectorScore?.toFixed(4) ?? '—' }}</td>
                <td>{{ row.keywordRank ?? '—' }} / {{ row.keywordScore?.toFixed(4) ?? '—' }}</td>
                <td>{{ row.rrfScore.toFixed(6) }}</td>
                <td>{{ row.finalRank || '—' }} / {{ row.rerankScore?.toFixed(4) ?? '—' }}</td>
              </tr></tbody
            >
          </table>
        </div>
        <h2>实际入模 Parent（{{ result.context.citations.length }}）</h2>
        <article
          v-for="citation in result.context.citations"
          :key="citation.citationId"
          class="notice"
        >
          <h3
            >[{{ citation.citationId }}] {{ citation.documentName }} · v{{ citation.documentVersion }} · Parent
            {{ citation.chunkId }}</h3
          >
          <CitationContent
            :citation="{
              ...citation,
              snippet: result.context.evidence[citation.citationId]?.content ?? citation.snippet,
            }"
          />
        </article>
        <p
          v-if="!result.context.citations.length"
          class="notice"
          >本次未选出可用证据，可核对知识库范围、文档索引和候选排名。</p
        >
      </template>
    </ResultDialog>
  </main>
</template>
<style scoped>
.debug-page {
  max-width: 1400px;
}
.debug-page form {
  display: grid;
  gap: 1rem;
  margin: 22px 0 30px;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.debug-page form > label {
  display: grid;
  gap: 8px;
  font-size: 13px;
}
.debug-page form > button {
  justify-self: start;
}
.debug-page fieldset label {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  margin-right: 1rem;
}
.debug-page pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.debug-table {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.debug-table table {
  width: 100%;
  border-collapse: collapse;
}
.debug-table th,
.debug-table td {
  text-align: left;
  padding: 0.75rem;
  border-bottom: 1px solid var(--border);
}
</style>
