<script setup lang="ts">
import { confidenceLevel, verificationStatus } from '../constants/chat'
import { computed } from 'vue'

const props = defineProps<{ status: string; confidence: string; generating: boolean; claims?: string[] | undefined }>()

/** 只有已完成的 SUPPORTED 显示证据支持，其余状态明确显示限制。 */
const label = computed(() => {
  if (props.generating) {
    return '回答尚在生成或核验中'
  }
  const labels: Record<string, string> = {
    [verificationStatus.supported]: '回答获证据支持',
    [verificationStatus.partiallySupported]: '仅部分内容获证据支持，请核对原文',
    [verificationStatus.unsupported]: '当前回答缺少证据支持',
    [verificationStatus.unknown]: '核验未完成，回答尚未核实',
  }
  return labels[props.status] ?? labels[verificationStatus.unknown]
})

/** 验证状态决定提示颜色，不能以置信度或相似度替代最终核验。 */
const supported = computed(() => !props.generating && props.status === verificationStatus.supported)
</script>

<template>
  <aside
    :class="['notice', 'verification-notice', { error: !supported }]"
    role="status"
  >
    <strong>证据校验：{{ label }}</strong>
    <span>
      · 置信度：{{
        { [confidenceLevel.high]: '高', [confidenceLevel.medium]: '中', [confidenceLevel.low]: '低' }[confidence] ??
        '低'
      }}</span
    >
    <ul v-if="claims?.length">
      <li
        v-for="claim in claims"
        :key="claim"
        >{{ claim }}</li
      >
    </ul>
  </aside>
</template>
