<script setup lang="ts">
import type { AdaptiveTrace } from '../types/retrieval'

defineProps<{ trace: AdaptiveTrace | null }>()
</script>

<template>
  <section v-if="trace?.plan">
    <p>独立查询：{{ trace.plan.query }}</p>
    <p>路线：{{ trace.plan.route }} · 原因：{{ trace.plan.reasonCode }} · 回退：{{ trace.plan.fallback }}</p>
    <p>规划 {{ trace.planningMs }} ms · 检索 {{ trace.retrievalMs }} ms</p>
    <ul>
      <li
        v-for="(search, index) in trace.searches"
        :key="index"
      >
        查询 {{ index + 1 }}：Vector {{ search.vectorStatus }} · FTS {{ search.keywordStatus }} · Rerank
        {{ search.rerankStatus }}
      </li>
    </ul>
    <details>
      <summary>检索查询（HyDE 仅用于检索）</summary>
      <pre>{{ JSON.stringify(trace.plan.queries, null, 2) }}</pre>
    </details>
  </section>
  <p
    v-else-if="trace"
    class="notice"
    >该请求没有可展示的自动路线记录。</p
  >
</template>

<style scoped>
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
