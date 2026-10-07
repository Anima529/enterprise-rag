<script setup lang="ts">
import SourcePreview from './SourcePreview.vue'
import type { Citation } from '../types/chat'

defineProps<{ citation: Citation }>()
</script>
<template>
  <SourcePreview
    :preview="citation.preview ?? []"
    :tables="citation.tables ?? []"
    :text="citation.snippet"
  />
</template>
