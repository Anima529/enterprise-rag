<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import AppIcon from './AppIcon.vue'

const props = withDefaults(
  defineProps<{ open: boolean; title: string; busy?: boolean; error?: string | null; loadingText?: string }>(),
  { busy: false, error: null, loadingText: '正在读取结果…' },
)
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const titleId = useId()

/** 使用原生模态弹窗管理焦点；等待挂载后再次检查状态，避免已关闭的弹窗被迟到更新打开。 */
watch(
  () => props.open,
  async open => {
    if (open) {
      await nextTick()
      if (props.open && dialog.value && !dialog.value.open) {
        dialog.value.showModal()
      }
    } else {
      dialog.value?.close()
    }
  },
  { immediate: true },
)

/** 页面卸载时退出模态状态，释放背景交互与焦点限制。 */
onBeforeUnmount(() => {
  dialog.value?.close()
})

/** 通知所属页面清理当前结果并作废未完成的读取。 */
function close(): void {
  emit('close')
}

/** 同步浏览器关闭状态，程序响应父级关闭时无需重复通知。 */
function handleClose(): void {
  if (props.open) {
    close()
  }
}

/** 仅关闭弹窗外部的遮罩点击，保留正文、表格和按钮的正常交互。 */
function handleBackdrop(event: MouseEvent): void {
  const element = dialog.value
  if (!element || event.target !== element) {
    return
  }
  const bounds = element.getBoundingClientRect()
  if (
    event.clientX < bounds.left ||
    event.clientX > bounds.right ||
    event.clientY < bounds.top ||
    event.clientY > bounds.bottom
  ) {
    close()
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="result-dialog"
    :aria-labelledby="titleId"
    @cancel.prevent="close"
    @close="handleClose"
    @click="handleBackdrop"
  >
    <header class="result-dialog-header">
      <h2 :id="titleId">{{ title }}</h2>
      <button
        type="button"
        class="secondary icon-button"
        :aria-label="`关闭${title}`"
        autofocus
        @click="close"
        ><AppIcon name="close"
      /></button>
    </header>
    <div
      class="result-dialog-body"
      :aria-busy="busy"
    >
      <p
        v-if="busy"
        role="status"
        >{{ loadingText }}</p
      >
      <p
        v-if="error"
        class="notice error"
        role="alert"
        >{{ error }}</p
      >
      <slot />
    </div>
    <footer class="result-dialog-actions">
      <button
        type="button"
        class="secondary"
        @click="close"
        >关闭</button
      >
    </footer>
  </dialog>
</template>
