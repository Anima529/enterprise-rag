<script setup lang="ts">
import { previewType } from '../constants/contracts'
import { computed } from 'vue'
import type { SourcePreviewBlock, SourceTable } from '../types/source-preview'

const props = defineProps<{ preview?: SourcePreviewBlock[]; tables?: SourceTable[]; text: string }>()

/** 优先使用有序混合预览，兼容历史表格或纯文本来源。 */
const blocks = computed<SourcePreviewBlock[]>(() => {
  if (props.preview?.length) {
    return props.preview
  }
  if (props.tables?.length) {
    return props.tables.map(table => ({ type: previewType.table, table, text: null, truncated: table.truncated }))
  }
  return [{ type: previewType.text, text: props.text, table: null, truncated: false }]
})
</script>
<template>
  <template
    v-for="(block, blockIndex) in blocks"
    :key="blockIndex"
  >
    <div
      v-if="block.type === previewType.table && block.table"
      class="citation-table-wrap"
      tabindex="0"
      role="region"
      :aria-label="block.table.sheetName ? `来源表格：${block.table.sheetName}` : '来源表格'"
    >
      <table class="citation-table">
        <caption>{{ block.table.sheetName ? `工作表：${block.table.sheetName}` : '来源表格' }}</caption>
        <thead>
          <tr>
            <th
              v-for="column in block.table.columns"
              :key="column.key"
              scope="col"
            >
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, rowIndex) in block.table.rows"
            :key="`${row.rowNumber}-${rowIndex}`"
          >
            <td
              v-for="(column, columnIndex) in block.table.columns"
              :key="column.key"
            >
              {{ row.cells[columnIndex] || '（空）' }}
            </td>
          </tr>
        </tbody>
      </table>
      <p
        v-if="block.table.truncated"
        class="hint"
      >
        当前仅展示引用范围内的完整行，完整表格可下载原文件查看。
      </p>
    </div>
    <div v-else>
      <pre>{{ block.text }}</pre>
      <p
        v-if="block.truncated"
        class="hint"
      >
        此处仅包含表格行的局部原文，完整行可下载原文件查看。
      </p>
    </div>
  </template>
</template>
<style scoped>
.citation-table-wrap {
  max-width: 100%;
  overflow-x: auto;
  margin: 0.75rem 0;
}
.citation-table {
  width: 100%;
  border-collapse: collapse;
}
.citation-table th,
.citation-table td {
  border: 1px solid #d9e2ec;
  padding: 0.65rem 0.8rem;
  min-width: 6rem;
  text-align: left;
  vertical-align: top;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.citation-table th {
  background: #eef3f7;
}
.citation-table caption {
  text-align: left;
  color: #63768c;
  margin-bottom: 0.5rem;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.hint {
  color: #63768c;
  font-size: 0.85rem;
}
</style>
