<script setup lang="ts">
import { BYTES_PER_KIBIBYTE } from '../constants/units'
import {
  apiErrorCode,
  chunkType as chunkTypeCode,
  httpProtocol,
  taskStatus as taskStatusCode,
} from '../constants/contracts'
import { clientConfig, pageSizes } from '../config/client'
import SourcePreview from './SourcePreview.vue'
import AppIcon from './AppIcon.vue'
import ResultDialog from './ResultDialog.vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  deleteDocument,
  getDocument,
  getDocumentChunks,
  getDocumentDownload,
  getDocumentEmbedding,
  getDocumentTasks,
  getDocumentTaskSteps,
  getParsedBlocks,
  retryDocumentTask,
  getUploadPolicy,
  listDocuments,
  uploadDocument,
  uploadDocumentVersion,
  reindexDocument,
} from '../api/document'
import { ApiRequestError } from '../api/http'
import type {
  ChunkPreview,
  ChunkType,
  DocumentRecord,
  DocumentTask,
  EmbeddingPreview,
  IndexStep,
  ParsePreview,
  UploadPolicy,
} from '../types/document'
import { documentStatus, taskStatus, shouldPollDocuments } from '../types/document'

type PreviewKind = 'parsed' | 'chunks'

const props = defineProps<{ kbId: number; canWrite: boolean }>()

// 文档列表、上传入口与选中文档。
const records = ref<DocumentRecord[]>([])
const policy = ref<UploadPolicy | null>(null)
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const detail = ref<DocumentRecord | null>(null)
const detailOpen = ref(false)
const detailDialog = ref<HTMLDialogElement | null>(null)

// 详情中的任务步骤、解析正文、分块与向量索引使用各自的分页状态。
const tasks = ref<DocumentTask[]>([])
const steps = ref<IndexStep[]>([])
const stepsTotal = ref(0)
const stepPage = ref(1)
const parsed = ref<ParsePreview | null>(null)
const parsedPage = ref(1)
const chunks = ref<ChunkPreview | null>(null)
const embedding = ref<EmbeddingPreview | null>(null)
const chunkPage = ref(1)
const chunkType = ref<ChunkType | ''>('')
const chunksLoading = ref(false)
const previewKind = ref<PreviewKind | null>(null)

// 列表分页、操作反馈及版本管理。
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const progress = ref(0)
const error = ref('')
const notice = ref('')
const versionFile = ref<File | null>(null)
const removing = ref<number | null>(null)
let alive = true
let revision = 0
let selectedRevision = 0
let timer: ReturnType<typeof setTimeout> | undefined

/** 先读取上传策略，再加载文档列表；卸载后丢弃迟到响应。 */
onMounted(async () => {
  try {
    const uploadPolicy = await getUploadPolicy(props.kbId)
    if (alive) {
      policy.value = uploadPolicy
    }
  } catch (cause) {
    if (alive) {
      error.value = failure(cause)
    }
  }
  if (alive) {
    await load()
  }
})

/** 停止轮询并推进请求代次，阻止卸载后的状态更新。 */
onBeforeUnmount(() => {
  alive = false
  ++revision
  closeDetail()
  clearTimeout(timer)
})

/** 展示文档操作失败的业务原因，不在提示中附加追踪信息。 */
function failure(cause: unknown) {
  return cause instanceof ApiRequestError ? cause.message : '操作失败，请重试。'
}

/** 权限或来源失效时清空受保护内容，避免继续展示已撤销数据。 */
function denied(cause: unknown) {
  if (
    cause instanceof ApiRequestError &&
    (cause.status === httpProtocol.unauthorized ||
      cause.status === httpProtocol.forbidden ||
      cause.code === apiErrorCode.knowledgeBaseNotFound ||
      cause.code === apiErrorCode.knowledgeBaseDisabled)
  ) {
    records.value = []
    resetDetail()
    policy.value = null
    ++selectedRevision
  }
}

/** 将文件字节数格式化为界面使用的 KB 大小。 */
function bytes(size: number) {
  return `${(size / BYTES_PER_KIBIBYTE).toFixed(1)} KB`
}

/** 刷新文档和选中详情，根据索引任务状态安排后续轮询。 */
async function load() {
  clearTimeout(timer)
  const loadRevision = ++revision
  loading.value = true
  try {
    const documentPage = await listDocuments(props.kbId, page.value)
    if (!alive || loadRevision !== revision) {
      return
    }
    records.value = documentPage.records
    total.value = documentPage.total
    error.value = ''
    if (detail.value) {
      await inspect(detail.value.id, true)
    }
    // 失败文档可能仍有已排期的自动重试，因此轮询还需检查任务状态。
    const failedTasks = await Promise.all(
      records.value.filter(row => row.id !== detail.value?.id).map(row => getDocumentTasks(row.id)),
    )
    if (!alive || loadRevision !== revision) {
      return
    }
    const pollingTasks = [...tasks.value, ...failedTasks.flatMap(taskPage => taskPage.records)]
    if (shouldPollDocuments(records.value, pollingTasks)) {
      // 只在仍有活动任务时安排下一次列表查询。
      timer = setTimeout(() => {
        void load()
      }, clientConfig.documentPollIntervalMs)
    }
  } catch (cause) {
    if (alive && loadRevision === revision) {
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive && loadRevision === revision) {
      loading.value = false
    }
  }
}

/** 记录待上传文件并清除上一次操作反馈。 */
function choose(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  error.value = ''
  notice.value = ''
}

/** 按服务器策略预校验文件，上传后刷新列表和索引详情。 */
async function upload() {
  const file = selectedFile.value
  if (!file || !policy.value) {
    return
  }
  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  if (!policy.value.extensions.includes(extension)) {
    error.value = '仅支持 PDF、DOC、DOCX、XLSX、TXT、MD，不支持 HTML。'
    return
  }
  if (file.size > policy.value.maxBytes) {
    error.value = `文件不得超过 ${bytes(policy.value.maxBytes)}。`
    return
  }
  if (!file.size) {
    error.value = '文件不能为空。'
    return
  }
  busy.value = true
  progress.value = 0
  error.value = ''
  notice.value = ''
  try {
    // 上传回调仅更新存活组件，避免卸载后继续写入进度。
    const uploadResponse = await uploadDocument(props.kbId, file, percent => {
      if (alive) {
        progress.value = percent
      }
    })
    if (!alive) {
      return
    }
    selectedFile.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    notice.value = `文档 ${uploadResponse.documentId} 已上传，等待处理。`
    page.value = 1
    await load()
    await openDetail(uploadResponse.documentId)
  } catch (cause) {
    if (alive) {
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive) {
      busy.value = false
    }
  }
}

/** 清空选中文档及全部预览，避免切换文档或权限失效后保留旧内容。 */
function resetDetail() {
  closePreview()
  detail.value = null
  tasks.value = []
  steps.value = []
  stepsTotal.value = 0
  stepPage.value = 1
  parsed.value = null
  parsedPage.value = 1
  chunks.value = null
  embedding.value = null
  chunkPage.value = 1
  chunkType.value = ''
  chunksLoading.value = false
  versionFile.value = null
}

/** 关闭详情弹窗并使未完成的读取失效，后台列表仍按任务状态刷新。 */
function closeDetail() {
  ++selectedRevision
  detailDialog.value?.close()
  detailOpen.value = false
  resetDetail()
}

/** 立即打开独立弹窗，再读取文档详情与任务，加载失败时在弹窗内提示。 */
async function openDetail(id: number) {
  closeDetail()
  error.value = ''
  detailOpen.value = true
  detailDialog.value?.showModal()
  await inspect(id)
}

/** 从当前详情打开正文或分块预览，保留底层详情和各自的分页状态。 */
function openPreview(kind: PreviewKind): void {
  if (!detailOpen.value || !detail.value) {
    return
  }
  previewKind.value = kind
}

/** 退出预览返回原详情位置；读取只更新缓存，不会重新打开已关闭的预览。 */
function closePreview(): void {
  previewKind.value = null
}

/** 并行读取文档及各类预览，用选择代次防止旧详情覆盖新选择。 */
async function inspect(id: number, keepPage = false) {
  const detailRevision = ++selectedRevision
  chunksLoading.value = true
  try {
    const [record, taskPage] = await Promise.all([getDocument(id), getDocumentTasks(id)])
    if (!alive || detailRevision !== selectedRevision) {
      return
    }
    if (!keepPage || detail.value?.id !== id) {
      closePreview()
      stepPage.value = 1
      parsedPage.value = 1
      parsed.value = null
      chunkPage.value = 1
      chunkType.value = ''
      chunks.value = null
    }
    const latest = taskPage.records[0]
    const [history, preview, chunkPreview, embeddingPreview] = await Promise.all([
      latest ? getDocumentTaskSteps(id, latest.id, stepPage.value) : Promise.resolve({ records: [], total: 0 }),
      getParsedBlocks(id, parsedPage.value, record.currentVersion),
      getDocumentChunks(id, chunkPage.value, record.currentVersion, chunkType.value || undefined),
      getDocumentEmbedding(id, record.currentVersion),
    ])
    if (alive && detailRevision === selectedRevision) {
      detail.value = record
      tasks.value = taskPage.records
      steps.value = history.records
      stepsTotal.value = history.total
      parsed.value = preview
      chunks.value = chunkPreview
      embedding.value = embeddingPreview
    }
  } catch (cause) {
    if (alive && detailRevision === selectedRevision) {
      resetDetail()
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive && detailRevision === selectedRevision) {
      chunksLoading.value = false
    }
  }
}

/** 重新排队失败任务并刷新文档状态。 */
async function retry(taskId: number) {
  if (!detail.value) {
    return
  }
  const id = detail.value.id
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await retryDocumentTask(id, taskId)
    if (!alive) {
      return
    }
    notice.value = '任务已重新排队。'
    await load()
  } catch (cause) {
    if (alive) {
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive) {
      busy.value = false
    }
  }
}

/** 上传新版本或申请重建，携带当前版本以检测并发冲突。 */
async function buildVersion(reindex: boolean) {
  if (!detail.value || (!reindex && !versionFile.value)) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const uploadResponse = reindex
      ? await reindexDocument(detail.value.id, detail.value.currentVersion)
      : await uploadDocumentVersion(detail.value.id, detail.value.currentVersion, versionFile.value!)
    versionFile.value = null
    notice.value = `v${uploadResponse.documentVersion} 已排队；当前版保持可检索，完成后自动切换。`
    await load()
  } catch (cause) {
    denied(cause)
    error.value = failure(cause)
  } finally {
    if (alive) {
      busy.value = false
    }
  }
}

/** 记录用于创建新版本的原文件。 */
function chooseVersion(event: Event) {
  versionFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

/** 调整阶段记录页码并保留其他详情分页。 */
async function moveStepPage(delta: number) {
  stepPage.value += delta
  if (detail.value) {
    await inspect(detail.value.id, true)
  }
}

/** 调整解析预览页码并重新读取选中文档。 */
async function moveParsedPage(delta: number) {
  parsedPage.value += delta
  if (detail.value) {
    await inspect(detail.value.id, true)
  }
}

/** 调整 Chunk 预览页码并重新读取选中文档。 */
async function moveChunkPage(delta: number) {
  chunkPage.value += delta
  if (detail.value) {
    await inspect(detail.value.id, true)
  }
}

/** 切换 Chunk 类型后从第一页刷新结果。 */
async function filterChunks() {
  chunkPage.value = 1
  if (detail.value) {
    await inspect(detail.value.id, true)
  }
}

/** 获取最新授权下载地址后打开原文件。 */
async function download(id: number) {
  busy.value = true
  error.value = ''
  try {
    const download = await getDocumentDownload(id)
    if (alive) {
      window.location.assign(download.url)
    }
  } catch (cause) {
    if (alive) {
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive) {
      busy.value = false
    }
  }
}

/** 删除文档，清理选中详情并处理最后一条记录的分页回退。 */
async function remove(id: number) {
  busy.value = true
  error.value = ''
  try {
    await deleteDocument(id)
    if (!alive) {
      return
    }
    removing.value = null
    if (detail.value?.id === id) {
      closeDetail()
    }
    if (records.value.length === 1 && page.value > 1) {
      --page.value
    }
    await load()
  } catch (cause) {
    if (alive) {
      denied(cause)
      error.value = failure(cause)
    }
  } finally {
    if (alive) {
      busy.value = false
    }
  }
}

/** 调整文档列表页码并刷新列表。 */
async function movePage(delta: number) {
  page.value += delta
  await load()
}

/** 只关闭弹窗外的遮罩点击，正文、预览和任务操作保持正常响应。 */
function handleDetailBackdrop(event: MouseEvent) {
  const dialog = detailDialog.value
  if (!dialog || event.target !== dialog) {
    return
  }
  const bounds = dialog.getBoundingClientRect()
  if (
    event.clientX < bounds.left ||
    event.clientX > bounds.right ||
    event.clientY < bounds.top ||
    event.clientY > bounds.bottom
  ) {
    closeDetail()
  }
}
</script>

<template>
  <section class="card documents-section">
    <div class="page-header">
      <h2>知识库文档</h2>
      <button
        class="secondary"
        :disabled="loading || busy"
        @click="load"
      >
        刷新文档
      </button>
    </div>
    <p class="hint"> 上传后自动排队处理，原文件可随时下载。失败任务可查看原因和步骤记录，有写权限时可重试。 </p>
    <p
      v-if="error && !detailOpen"
      class="notice error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="notice && !detailOpen"
      class="notice success"
      role="status"
    >
      {{ notice }}
    </p>
    <form
      v-if="canWrite && policy"
      class="document-upload"
      @submit.prevent="upload"
    >
      <label :for="`document-file-${kbId}`">选择文档（上限 {{ bytes(policy.maxBytes) }}；TXT / MD 使用 UTF-8）</label>
      <input
        :id="`document-file-${kbId}`"
        ref="fileInput"
        type="file"
        :accept="policy.extensions.join(',')"
        :disabled="busy"
        @change="choose"
      />
      <button :disabled="busy || !selectedFile">
        {{ busy ? '正在处理…' : '上传文档' }}
      </button>
      <progress
        v-if="busy && selectedFile"
        :value="progress"
        max="100"
        aria-label="文档上传进度"
      />
    </form>
    <div class="table-scroll">
      <table>
        <thead
          ><tr><th>文件名 / 类型</th><th>大小 / 版本</th><th>索引状态</th><th>上传人</th><th>操作</th></tr></thead
        >
        <tbody>
          <tr
            v-for="record in records"
            :key="record.id"
          >
            <td class="document-name">
              {{ record.name }}<br /><small
                >{{ record.sourceFormat }} · {{ new Date(record.createdAt).toLocaleString() }}</small
              >
            </td>
            <td>{{ bytes(record.sizeBytes) }}<br />v{{ record.currentVersion }}</td>
            <td>
              <span
                class="badge"
                :class="documentStatus[record.status].tone"
                >{{ documentStatus[record.status].label }}</span
              >
            </td>
            <td>{{ record.createdByUsername }}</td>
            <td>
              <div class="link-row">
                <button
                  class="secondary"
                  :disabled="busy"
                  @click="openDetail(record.id)"
                >
                  详情与任务
                </button>
                <button
                  class="secondary"
                  :disabled="busy"
                  @click="download(record.id)"
                >
                  下载原文件
                </button>
                <button
                  v-if="canWrite && removing !== record.id"
                  class="danger"
                  :disabled="busy"
                  @click="removing = record.id"
                >
                  删除
                </button>
                <template v-if="canWrite && removing === record.id">
                  <span>确认删除？</span
                  ><button
                    class="danger"
                    :disabled="busy"
                    @click="remove(record.id)"
                  >
                    确认
                  </button>
                  <button
                    class="secondary"
                    :disabled="busy"
                    @click="removing = null"
                  >
                    取消
                  </button>
                </template>
              </div>
            </td>
          </tr>
          <tr v-if="!records.length">
            <td colspan="5">
              {{ loading ? '正在加载…' : '暂无文档' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <button
        class="secondary"
        :disabled="loading || busy || page <= 1"
        @click="movePage(-1)"
      >
        上一页
      </button>
      <span>第 {{ page }} 页 · 共 {{ total }} 个文档</span>
      <button
        class="secondary"
        :disabled="loading || busy || page * pageSizes.default >= total"
        @click="movePage(1)"
      >
        下一页
      </button>
    </div>
    <dialog
      ref="detailDialog"
      class="document-dialog"
      :aria-labelledby="`document-dialog-title-${kbId}`"
      @cancel.prevent="closeDetail"
      @click="handleDetailBackdrop"
    >
      <header class="document-dialog-header">
        <h3 :id="`document-dialog-title-${kbId}`">文档详情与任务</h3>
        <button
          type="button"
          class="secondary icon-button"
          aria-label="关闭文档详情"
          autofocus
          @click="closeDetail"
          ><AppIcon name="close"
        /></button>
      </header>
      <div
        class="document-dialog-body"
        :aria-busy="chunksLoading"
      >
        <p
          v-if="chunksLoading"
          role="status"
          >{{ detail ? '正在刷新文档详情…' : '正在读取文档详情与任务…' }}</p
        >
        <p
          v-if="error"
          class="notice error"
          role="alert"
          >{{ error }}</p
        >
        <p
          v-if="notice"
          class="notice success"
          role="status"
          >{{ notice }}</p
        >
        <section
          v-if="detail"
          class="document-detail"
        >
          <h3>{{ detail.name }} · 任务状态</h3>
          <p
            >文档 ID {{ detail.id }} · 当前版本 {{ detail.currentVersion }} ·
            {{ documentStatus[detail.status].label }}</p
          >
          <div class="link-row preview-actions">
            <button
              type="button"
              class="secondary"
              :disabled="busy || chunksLoading"
              @click="openPreview('parsed')"
            >
              解析正文预览
            </button>
            <button
              type="button"
              class="secondary"
              :disabled="busy || chunksLoading"
              @click="openPreview('chunks')"
            >
              父子分块预览
            </button>
          </div>
          <div
            v-if="canWrite"
            class="version-controls"
          >
            <p class="hint"> 上传新版或重建都会创建递增版本。构建、失败期间保留当前版，全部完成后自动切换。 </p>
            <input
              type="file"
              aria-label="新版文件"
              :accept="policy?.extensions.join(',')"
              :disabled="busy"
              @change="chooseVersion"
            />
            <button
              class="secondary"
              :disabled="
                busy ||
                !versionFile ||
                tasks.some(task => task.status === taskStatusCode.pending || task.status === taskStatusCode.running)
              "
              @click="buildVersion(false)"
            >
              上传新版
            </button>
            <button
              class="secondary"
              :disabled="
                busy ||
                tasks.some(task => task.status === taskStatusCode.pending || task.status === taskStatusCode.running)
              "
              @click="buildVersion(true)"
            >
              重建索引
            </button>
          </div>
          <p class="hint"> SHA-256：{{ detail.sha256 }} </p>
          <p
            v-if="detail.errorCode || detail.errorMessage"
            class="notice error"
          >
            {{ detail.errorCode }}：{{ detail.errorMessage }}
          </p>
          <ul>
            <li
              v-for="task in tasks"
              :key="task.id"
            >
              任务 {{ task.id }} · v{{ task.documentVersion }} · {{ taskStatus[task.status] }} ·
              {{ documentStatus[task.stage].label }} · 已执行 {{ task.attemptCount }} 次 · 重试
              {{ task.retryCount }} 次<span v-if="task.errorCode || task.errorMessage">
                · {{ task.errorCode }}：{{ task.errorMessage }}</span
              >
              <span v-if="task.status === taskStatusCode.pending && task.retryCount > 0">
                · 下次尝试 {{ new Date(task.nextAttemptAt).toLocaleString() }}</span
              >
              <button
                v-if="
                  canWrite &&
                  task.status === taskStatusCode.failed &&
                  (task.documentVersion === detail.currentVersion ||
                    task.documentVersion === Math.max(...tasks.map(item => item.documentVersion)))
                "
                class="secondary"
                :disabled="busy"
                @click="retry(task.id)"
              >
                重试任务
              </button>
            </li>
          </ul>
          <h4>向量索引</h4>
          <p v-if="embedding?.available">
            {{ embedding.indexed ? '索引完成' : '向量已保存，等待索引校验完成' }} · {{ embedding.model }} ·
            {{ embedding.dimensions }} 维 · {{ embedding.childCount }} 个子块 · {{ embedding.batchCount }} 批 ·
            {{ embedding.callCount }} 次模型调用 · {{ embedding.totalTokens }} tokens · 耗时
            {{ embedding.durationMs }} ms
          </p>
          <p
            v-else
            class="muted"
          >
            尚无完整向量索引，请查看任务状态和失败原因。
          </p>
          <h4>最新任务的步骤记录</h4>
          <ol>
            <li
              v-for="step in steps"
              :key="step.id"
            >
              第 {{ step.attempt }} 次 · {{ documentStatus[step.stage].label }} · {{ taskStatus[step.status] }} ·
              {{ new Date(step.startedAt).toLocaleString() }}
              <span v-if="step.finishedAt">
                · 耗时
                {{ Math.max(0, new Date(step.finishedAt).getTime() - new Date(step.startedAt).getTime()) }} ms</span
              >
              <span v-if="step.errorCode"> · {{ step.errorCode }}：{{ step.errorMessage }}</span>
            </li>
          </ol>
          <div
            v-if="stepsTotal > pageSizes.indexSteps"
            class="pagination"
          >
            <button
              class="secondary"
              :disabled="busy || stepPage <= 1"
              @click="moveStepPage(-1)"
            >
              上一页步骤
            </button>
            <span>第 {{ stepPage }} 页 · 共 {{ stepsTotal }} 步</span>
            <button
              class="secondary"
              :disabled="busy || stepPage * pageSizes.indexSteps >= stepsTotal"
              @click="moveStepPage(1)"
            >
              下一页步骤
            </button>
          </div>
        </section>
      </div>
      <footer class="document-dialog-actions">
        <button
          v-if="detail"
          type="button"
          class="secondary"
          :disabled="busy || chunksLoading"
          @click="inspect(detail.id, true)"
        >
          刷新详情
        </button>
        <button
          type="button"
          class="secondary"
          @click="closeDetail"
        >
          关闭
        </button>
      </footer>
    </dialog>
    <ResultDialog
      :open="previewKind !== null"
      :title="previewKind === 'parsed' ? '解析正文预览' : '父子分块预览'"
      :busy="chunksLoading"
      :error="error"
      loading-text="正在刷新文档预览…"
      @close="closePreview"
    >
      <p
        v-if="detail"
        class="hint"
      >
        {{ detail.name }} · 文档 ID {{ detail.id }}
      </p>
      <section
        v-if="previewKind === 'parsed' && parsed"
        class="parsed-preview"
      >
        <h4>解析正文预览 · v{{ parsed.documentVersion }}</h4>
        <p
          v-if="!parsed.available"
          class="hint"
        >
          暂无解析结果，请查看解析步骤与失败原因。
        </p>
        <template v-else>
          <p class="hint"> 共 {{ parsed.total }} 个正文块 · {{ parsed.characterCount }} 字符 </p>
          <p
            v-if="parsed.warnings.length"
            class="notice"
          >
            解析提示：{{ parsed.warnings.join('；') }}
          </p>
          <article
            v-for="block in parsed.records"
            :key="block.blockIndex"
            class="parsed-block"
          >
            <p class="hint">
              正文块 {{ block.blockIndex + 1 }} · {{ block.metadata.kind }}
              <span v-if="block.page"> · 第 {{ block.page }} 页</span>
              <span v-if="block.section"> · {{ block.section }}</span>
              <span v-if="block.metadata.lineStart">
                · 行 {{ block.metadata.lineStart }}–{{ block.metadata.lineEnd }}</span
              >
              <span v-if="block.metadata.sheetName">
                · 工作表 {{ block.metadata.sheetName }} · 行 {{ block.metadata.rowStart }}–{{
                  block.metadata.rowEnd
                }}</span
              >
              <span v-if="block.metadata.paragraphIndex"> · 第 {{ block.metadata.paragraphIndex }} 段</span>
            </p>
            <SourcePreview
              :preview="block.preview ?? []"
              :text="block.text"
            />
          </article>
          <div
            v-if="parsed.total > pageSizes.default"
            class="pagination"
          >
            <button
              class="secondary"
              :disabled="busy || chunksLoading || parsedPage <= 1"
              @click="moveParsedPage(-1)"
            >
              上一页正文
            </button>
            <span>第 {{ parsedPage }} 页 · 共 {{ parsed.total }} 块</span>
            <button
              class="secondary"
              :disabled="busy || chunksLoading || parsedPage * pageSizes.default >= parsed.total"
              @click="moveParsedPage(1)"
            >
              下一页正文
            </button>
          </div>
        </template>
      </section>
      <section
        v-if="previewKind === 'chunks' && chunks"
        class="chunk-preview"
        :aria-busy="chunksLoading"
      >
        <h4>父子分块预览 · v{{ chunks.documentVersion }}</h4>
        <p
          v-if="!chunks.available"
          class="hint"
        >
          暂无分块结果
        </p>
        <template v-else>
          <div class="link-row">
            <p class="hint">
              {{ chunks.parentCount }} 个父块 · {{ chunks.childCount }} 个子块 · {{ chunks.tokenizer }}
            </p>
            <label>
              类型
              <select
                v-model="chunkType"
                :disabled="busy || chunksLoading"
                @change="filterChunks"
              >
                <option value="">全部</option>
                <option :value="chunkTypeCode.parent">父块</option>
                <option :value="chunkTypeCode.child">子块</option>
              </select>
            </label>
          </div>
          <article
            v-for="chunk in chunks.records"
            :key="chunk.id"
            class="parsed-block"
          >
            <p class="hint">
              {{ chunk.chunkType === chunkTypeCode.parent ? '父块' : '子块' }} {{ chunk.id }} · 顺序
              {{ chunk.chunkIndex + 1 }} · {{ chunk.tokenCount }} tokens
              <span v-if="chunk.parentChunkId"> · 父块 {{ chunk.parentChunkId }}</span>
              <span v-if="chunk.section"> · {{ chunk.section }}</span>
            </p>
            <ul class="chunk-sources">
              <li
                v-for="(source, index) in chunk.metadata.sources"
                :key="index"
              >
                正文块 {{ source.blockIndex + 1 }} · 字符 {{ source.charStart }}–{{ source.charEnd }}
                <span v-if="source.page"> · 第 {{ source.page }} 页</span>
                <span v-if="source.metadata.lineStart">
                  · 行 {{ source.metadata.lineStart }}–{{ source.metadata.lineEnd }}</span
                >
                <span v-if="source.metadata.sheetName"> · 工作表 {{ source.metadata.sheetName }}</span>
                <span v-if="source.metadata.rowStart">
                  · 行 {{ source.metadata.rowStart }}–{{ source.metadata.rowEnd }}</span
                >
                <span v-if="source.metadata.paragraphIndex"> · 第 {{ source.metadata.paragraphIndex }} 段</span>
              </li>
            </ul>
            <SourcePreview
              :preview="chunk.preview ?? []"
              :text="chunk.content"
            />
          </article>
          <div
            v-if="chunks.total > pageSizes.default"
            class="pagination"
          >
            <button
              class="secondary"
              :disabled="busy || chunksLoading || chunkPage <= 1"
              @click="moveChunkPage(-1)"
            >
              上一页分块
            </button>
            <span>第 {{ chunks.page }} 页 · 共 {{ chunks.total }} 块</span>
            <button
              class="secondary"
              :disabled="busy || chunksLoading || chunkPage * pageSizes.default >= chunks.total"
              @click="moveChunkPage(1)"
            >
              下一页分块
            </button>
          </div>
        </template>
      </section>
    </ResultDialog>
  </section>
</template>

<style scoped>
.parsed-block {
  border-top: 1px solid var(--border);
  padding: 0.75rem 0;
}
.parsed-block pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 24rem;
  overflow: auto;
  font: inherit;
}
.preview-actions {
  margin-bottom: 20px;
}
.chunk-preview select {
  padding: 6px 10px;
  font: inherit;
  border: 1px solid var(--border);
  border-radius: 4px;
}
.chunk-sources {
  color: var(--muted);
  font-size: 13px;
  padding-left: 20px;
}
</style>
