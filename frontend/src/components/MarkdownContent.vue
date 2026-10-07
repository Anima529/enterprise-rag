<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps<{ content: string; citationIds: string[] }>()
const emit = defineEmits<{ citation: [id: string] }>()

/** 根据正文和允许引用编号生成安全的 Markdown 展示内容。 */
const rendered = computed(() => renderMarkdown(props.content, props.citationIds))

/** 识别引用标记点击并通知父组件打开对应来源。 */
function click(event: MouseEvent) {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>('button[data-citation]')
  if (button?.dataset.citation) {
    emit('citation', button.dataset.citation)
  }
}
</script>
<template>
  <!-- HTML is produced only by the renderer with raw HTML disabled, images suppressed and links validated. -->
  <!-- eslint-disable vue/no-v-html -->
  <div
    class="markdown-content"
    @click="click"
    v-html="rendered"
  />
  <!-- eslint-enable vue/no-v-html -->
</template>
