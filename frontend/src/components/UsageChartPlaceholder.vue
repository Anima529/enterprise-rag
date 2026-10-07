<script setup lang="ts">
import AppIcon from './AppIcon.vue'

defineProps<{ error?: Error }>()
</script>

<template>
  <div
    class="usage-chart-placeholder"
    role="status"
  >
    <AppIcon name="chart" />
    <strong>{{ error ? '图表暂时未加载' : '图表加载中…' }}</strong>
    <span v-if="error">请刷新页面重试，下方统计表仍可查看。</span>
  </div>
</template>

<style scoped>
.usage-chart-placeholder {
  display: flex;
  min-height: 270px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-radius: 10px;
  color: var(--muted);
  background: #f8faf5;
  text-align: center;
}
.usage-chart-placeholder .app-icon {
  width: 32px;
  height: 32px;
  color: #8a9d76;
}
.usage-chart-placeholder strong {
  font-size: 14px;
  font-weight: 550;
}
.usage-chart-placeholder span {
  font-size: 12px;
}
</style>
