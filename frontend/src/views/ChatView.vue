<script setup lang="ts">
import { documentFormat } from '../constants/contracts'
import { confidenceLevel, messageRole, messageStatus, streamEventType, verificationStatus } from '../constants/chat'
import { clientConfig, pageSizes } from '../config/client'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownContent from '../components/MarkdownContent.vue'
import CitationContent from '../components/CitationContent.vue'
import VerificationNotice from '../components/VerificationNotice.vue'
import AppIcon from '../components/AppIcon.vue'
import ResultDialog from '../components/ResultDialog.vue'
import RetrievalTraceContent from '../components/RetrievalTraceContent.vue'
import { useAuthStore } from '../stores/auth'
import {
  createConversation,
  deleteConversation,
  getConversation,
  getMessages,
  getCitationSource,
  getCitationPdf,
  listConversations,
  renameConversation,
} from '../api/conversation'
import { cancelChat, streamChat } from '../api/chat'
import { listKnowledgeBases } from '../api/knowledgeBase'
import { getDocumentDownload } from '../api/document'
import { getRetrievalTrace } from '../api/retrieval'
import { ApiRequestError } from '../api/http'
import type { ChatMessage, Citation, CitationSource, Conversation, StreamEvent } from '../types/chat'
import type { KnowledgeBase } from '../types/knowledgeBase'
import type { AdaptiveTrace } from '../types/retrieval'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// 会话列表、分页及当前会话的历史消息。
const conversations = ref<Conversation[]>([])
const historyOpen = ref(false)
const messageList = ref<HTMLElement | null>(null)
const conversationPage = ref(1)
const conversationTotal = ref(0)
const current = ref<Conversation | null>(null)
const messages = ref<ChatMessage[]>([])

// 本次提问的知识库范围与流式请求状态。
const knowledgeBases = ref<KnowledgeBase[]>([])
const selected = ref<number[]>([])
const question = ref('')
const questionInput = ref<HTMLTextAreaElement | null>(null)
const busy = ref(false)
const status = ref('')
const error = ref('')

// 会话管理与引用预览交互。
const title = ref('')
const citation = ref<Citation | null>(null)
const citationSource = ref<CitationSource | null>(null)
const citationMessageId = ref<number | null>(null)
const citationLoading = ref(false)
const citationError = ref('')
const citationDialog = ref<HTMLDialogElement | null>(null)
let citationRevision = 0
const traceDialogOpen = ref(false)
const trace = ref<AdaptiveTrace | null>(null)
const traceLoading = ref(false)
const traceError = ref('')
let traceRevision = 0
const confirmingDelete = ref(false)
let controller: AbortController | undefined
let requestId: string | undefined
let alive = true
let revision = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
const scrollFollowThresholdPx = 96

const questionSuggestions = [
  { title: '查找工作流程', question: '请根据所选知识库，整理相关工作流程和注意事项。', icon: 'file' },
  { title: '梳理关键内容', question: '请总结所选知识库的主要内容，并列出对应的原文依据。', icon: 'library' },
  { title: '核对制度要求', question: '请根据所选知识库，说明相关制度的适用范围和具体要求。', icon: 'lock' },
] as const

/** 发送按钮与键盘快捷键共用相同条件，避免生成期间重复提交。 */
const canSend = computed(
  () =>
    !busy.value &&
    Boolean(question.value.trim()) &&
    Boolean(selected.value.length) &&
    !messages.value.some(message => message.status === messageStatus.generating),
)

/** 路由会话变化时加载对应历史，生成期间保留当前流状态。 */
watch(
  () => route.params.conversationId,
  () => {
    closeCitation()
    closeTrace()
    if (!busy.value) {
      void loadRoute()
    }
  },
)

/** 位于消息底部时跟随新增内容；员工向上阅读历史后不强制拉回。 */
watch(
  () => [messages.value.length, messages.value.at(-1)?.content],
  async () => {
    const list = messageList.value
    if (!list) {
      return
    }
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight <= scrollFollowThresholdPx
    if (nearBottom) {
      await nextTick()
      list.scrollTop = list.scrollHeight
    }
  },
)

/** 加载会话侧栏和全部可读知识库，再恢复路由指定的历史。 */
onMounted(async () => {
  try {
    await sidebar()
    let page = 1
    while (alive) {
      const knowledgeBasePage = await listKnowledgeBases(page++)
      knowledgeBases.value.push(...knowledgeBasePage.records)
      if (knowledgeBases.value.length >= knowledgeBasePage.total) {
        break
      }
    }
    if (knowledgeBases.value[0]) {
      selected.value = [knowledgeBases.value[0].id]
    }
    await loadRoute()
  } catch (cause) {
    if (alive) {
      error.value = failure(cause)
    }
  }
})

/** 停止历史刷新、取消活动请求并断开流连接。 */
onBeforeUnmount(() => {
  alive = false
  ++revision
  closeCitation()
  closeTrace()
  clearTimeout(refreshTimer)
  if (requestId) {
    // 卸载期间的取消失败无需展示，连接仍会在本地中断。
    void cancelChat(requestId).catch(() => {
    })
  }
  controller?.abort()
})

/** 将业务或网络异常转换为聊天页可读提示。 */
function failure(cause: unknown) {
  if (cause instanceof ApiRequestError) {
    return cause.message
  }
  if (cause instanceof Error) {
    return cause.message
  }
  return '操作失败'
}

/** 刷新当前页会话侧栏和总数。 */
async function sidebar() {
  const conversationPageResponse = await listConversations(conversationPage.value)
  if (alive) {
    conversations.value = conversationPageResponse.records
    conversationTotal.value = conversationPageResponse.total
  }
}

/** 分页恢复完整消息历史，对仍在生成的会话安排查询刷新。 */
async function history(id: number) {
  clearTimeout(refreshTimer)
  const ticket = ++revision
  const conversation = await getConversation(id)
  const firstMessagePage = await getMessages(id)
  const rows = [...firstMessagePage.records]
  // 按时间顺序恢复完整历史，避免再次发起生成请求。
  for (let page = 2; rows.length < firstMessagePage.total; page++) {
    rows.push(...(await getMessages(id, page)).records)
  }
  if (!alive || ticket !== revision) {
    return
  }
  current.value = conversation
  title.value = conversation.title
  messages.value = rows
  if (rows.some(row => row.status === messageStatus.generating)) {
    status.value = '该会话仍在生成；可取消或等待历史更新。'
    // 历史恢复只查询生成结果，不重新发起模型请求。
    refreshTimer = setTimeout(() => {
      // 仅将存活页面的刷新失败反馈给用户。
      void history(id).catch(cause => {
        if (alive) {
          error.value = failure(cause)
        }
      })
    }, clientConfig.chatPollIntervalMs)
  } else if (!busy.value) {
    status.value = ''
  }
}

/** 校验路由会话 ID 并切换历史，空路由恢复新会话状态。 */
async function loadRoute() {
  closeCitation()
  closeTrace()
  confirmingDelete.value = false
  const id = Number(route.params.conversationId)
  if (!route.params.conversationId) {
    ++revision
    current.value = null
    messages.value = []
    title.value = ''
    return
  }
  if (!Number.isSafeInteger(id) || id <= 0) {
    error.value = '会话编号无效'
    return
  }
  try {
    await history(id)
  } catch (cause) {
    if (alive) {
      current.value = null
      messages.value = []
      error.value = failure(cause)
    }
  }
}

/** 按 SSE 事件类型更新占位回答、引用、用量与终态。 */
function handleEvent(event: StreamEvent, reply: ChatMessage) {
  switch (event.type) {
    case streamEventType.status:
      status.value = event.data.message
      break
    case streamEventType.content:
      reply.content += event.data.delta
      break
    case streamEventType.citation:
      if (!reply.citations.some(item => item.citationId === event.data.citationId)) {
        reply.citations.push(event.data)
      }
      break
    case streamEventType.verification:
      reply.confidenceLevel = event.data.confidenceLevel
      reply.faithfulnessStatus = event.data.status
      reply.verificationDetails = {
        ...event.data,
        coverage: event.data.coverage ?? 0,
        unsupportedClaims: event.data.unsupportedClaims ?? [],
        conflictingEvidence: event.data.conflictingEvidence ?? false,
        reasonCode: event.data.reasonCode ?? verificationStatus.unknown,
      }
      break
    case streamEventType.usage:
      reply.inputTokens = event.data.inputTokens
      reply.outputTokens = event.data.outputTokens
      reply.estimatedCost = event.data.estimatedCost
      break
    case streamEventType.done:
      reply.id = event.data.messageId
      reply.status = event.data.finishReason as ChatMessage['status']
      break
    case streamEventType.error:
      reply.status = messageStatus.failed
      reply.errorCode = event.data.code
      error.value = event.data.message
      break
  }
}

/** 创建必要会话及占位消息，消费流式回答后从服务器恢复最终历史。 */
async function send() {
  if (
    busy.value ||
    !question.value.trim() ||
    !selected.value.length ||
    messages.value.some(row => row.status === messageStatus.generating)
  ) {
    return
  }
  busy.value = true
  error.value = ''
  closeCitation()
  status.value = '正在连接…'
  controller = new AbortController()
  requestId = undefined
  let id = current.value?.id
  try {
    const text = question.value.trim()
    if (!id) {
      const conversation = await createConversation(text.slice(0, clientConfig.conversationTitlePreviewCharacters))
      id = conversation.id
      current.value = conversation
      await router.replace(`/chat/${id}`)
      await sidebar()
    }
    const [userMessage, reply] = createPendingMessages(id, text)
    messages.value.push(userMessage, reply)
    question.value = ''
    // 重新读取响应式代理，使后续增量正文能够立即渲染。
    const visibleReply = messages.value[messages.value.length - 1]!
    // 流事件只更新存活页面；请求 ID 用于取消和历史恢复。
    await streamChat(
      { conversationId: id, knowledgeBaseIds: [...selected.value], message: text },
      controller.signal,
      event => {
        if (alive) {
          handleEvent(event, visibleReply)
        }
      },
      value => {
        requestId = value
        visibleReply.requestId = value
      },
    )
  } catch (cause) {
    if (alive && !(cause instanceof DOMException && cause.name === 'AbortError')) {
      error.value = failure(cause)
    }
  } finally {
    busy.value = false
    controller = undefined
    requestId = undefined
    if (alive && id) {
      try {
        await history(id)
        await sidebar()
      } catch (cause) {
        error.value = failure(cause)
      }
    }
    if (alive) {
      status.value = ''
    }
  }
}

/** 创建仅用于即时展示的用户消息和生成占位回答。 */
function createPendingMessages(conversationId: number, content: string): [ChatMessage, ChatMessage] {
  const messageDefaults = {
    conversationId,
    requestId: '',
    traceId: '',
    errorCode: null,
    confidenceLevel: confidenceLevel.low,
    faithfulnessStatus: verificationStatus.unknown,
    inputTokens: null,
    outputTokens: null,
    createdAt: new Date().toISOString(),
    citations: [],
  }
  const userMessage: ChatMessage = {
    ...messageDefaults,
    id: -1,
    role: messageRole.user,
    content,
    status: messageStatus.completed,
  }
  const reply: ChatMessage = {
    ...messageDefaults,
    citations: [],
    id: -2,
    role: messageRole.assistant,
    content: '',
    status: messageStatus.generating,
  }
  return [userMessage, reply]
}

/** 取消活动生成并中断连接，必要时重新读取历史终态。 */
async function stop() {
  const activeRequest = requestId ?? messages.value.find(row => row.status === messageStatus.generating)?.requestId
  try {
    if (activeRequest) {
      await cancelChat(activeRequest)
    }
  } catch (cause) {
    if (alive) {
      error.value = failure(cause)
    }
  } finally {
    controller?.abort()
    if (!busy.value && current.value) {
      await history(current.value.id)
    }
  }
}

/** 提交非空会话标题并刷新侧栏排序。 */
async function rename() {
  if (!current.value || !title.value.trim()) {
    return
  }
  try {
    current.value = await renameConversation(current.value.id, title.value.trim())
    await sidebar()
  } catch (cause) {
    error.value = failure(cause)
  }
}

/** 删除当前会话后返回新会话页并刷新侧栏。 */
async function remove() {
  if (!current.value) {
    return
  }
  try {
    await deleteConversation(current.value.id)
    confirmingDelete.value = false
    await router.push('/chat')
    await sidebar()
  } catch (cause) {
    error.value = failure(cause)
  }
}

/** 按引用保存的文档版本获取授权原文件地址。 */
async function download() {
  if (!citation.value) {
    return
  }
  try {
    const download = await getDocumentDownload(citation.value.documentId, citation.value.documentVersion)
    window.location.assign(download.url)
  } catch (cause) {
    citationError.value = failure(cause)
  }
}

/** 关闭原文弹窗、恢复触发位置的焦点，并使迟到的预览响应失效。 */
function closeCitation() {
  ++citationRevision
  citationDialog.value?.close()
  citation.value = null
  citationSource.value = null
  citationMessageId.value = null
  citationLoading.value = false
  citationError.value = ''
}

/** 立即打开独立弹窗，再读取授权原文；关闭后的迟到响应不能重新展示内容。 */
async function openCitation(message: ChatMessage, citationId: string) {
  if (message.id < 1 || message.status === messageStatus.generating) {
    return
  }
  closeCitation()
  const ticket = citationRevision
  citationLoading.value = true
  citationDialog.value?.showModal()
  try {
    const source = await getCitationSource(message.conversationId, message.id, citationId)
    if (alive && ticket === citationRevision) {
      citation.value = source.citation
      citationSource.value = source
      citationMessageId.value = message.id
    }
  } catch (cause) {
    if (alive && ticket === citationRevision) {
      citationError.value = failure(cause)
    }
  } finally {
    if (ticket === citationRevision) {
      citationLoading.value = false
    }
  }
}

/** 重新鉴权后在独立窗口按引用页码打开 PDF，保留当前会话。 */
async function openPdf() {
  if (!current.value || !citation.value || !citationMessageId.value) {
    return
  }
  const source = citation.value
  const ticket = citationRevision
  const previewWindow = window.open('about:blank', '_blank')
  if (previewWindow) {
    previewWindow.opener = null
  }
  try {
    const preview = await getCitationPdf(current.value.id, citationMessageId.value, source.citationId)
    if (!alive || ticket !== citationRevision) {
      previewWindow?.close()
      return
    }
    const url = `${preview.url}#page=${source.page ?? 1}`
    if (previewWindow) {
      previewWindow.location.replace(url)
    } else {
      citationError.value = '浏览器阻止了原文窗口，请允许弹窗后重试。'
    }
  } catch (cause) {
    previewWindow?.close()
    if (alive && ticket === citationRevision) {
      citationError.value = failure(cause)
    }
  }
}

/** 调整侧栏页码并加载对应会话。 */
async function moveSidebar(delta: number) {
  conversationPage.value += delta
  await sidebar()
}

/** 示例仅填入问题草稿，员工可编辑后自行发送。 */
async function useSuggestion(suggestion: string) {
  question.value = suggestion
  await nextTick()
  questionInput.value?.focus()
}

/** Ctrl 或 Command 加 Enter 沿用表单发送条件，不改变原有请求流程。 */
function handleComposerKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing && canSend.value) {
    event.preventDefault()
    void send()
  }
}

/** 清理本次追踪并作废读取，关闭或切换会话后不得恢复迟到内容。 */
function closeTrace(): void {
  traceRevision++
  traceDialogOpen.value = false
  trace.value = null
  traceLoading.value = false
  traceError.value = ''
}

/** 在当前问答页面读取该回答的持久追踪，保持会话地址、阅读位置和输入内容。 */
async function openTrace(message: ChatMessage): Promise<void> {
  if (
    !auth.isAdmin ||
    message.role !== messageRole.assistant ||
    !message.requestId ||
    message.status === messageStatus.generating
  ) {
    return
  }
  closeTrace()
  const ticket = traceRevision
  traceLoading.value = true
  traceDialogOpen.value = true
  try {
    const requestTrace = await getRetrievalTrace(message.requestId)
    if (alive && ticket === traceRevision) {
      trace.value = requestTrace
    }
  } catch (cause) {
    if (alive && ticket === traceRevision) {
      traceError.value = cause instanceof ApiRequestError ? cause.message : '追踪读取失败，请重试。'
    }
  } finally {
    if (alive && ticket === traceRevision) {
      traceLoading.value = false
    }
  }
}

/** 仅在点击弹窗外的遮罩时关闭，保留正文、表格和按钮的正常点击。 */
function handleCitationBackdrop(event: MouseEvent) {
  const dialog = citationDialog.value
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
    closeCitation()
  }
}
</script>
<template>
  <main class="chat-shell">
    <aside class="chat-sidebar card">
      <p class="eyebrow">CONVERSATIONS</p>
      <h1>知识库问答</h1>
      <div class="chat-sidebar-actions"
        ><RouterLink
          class="chat-new"
          to="/chat"
          :aria-disabled="busy"
          @click="busy && $event.preventDefault()"
        >
          <AppIcon name="plus" />新会话
        </RouterLink>
        <button
          class="secondary conversation-toggle"
          :aria-expanded="historyOpen"
          aria-controls="conversation-history"
          :disabled="busy"
          @click="historyOpen = !historyOpen"
          ><AppIcon name="chat" />会话记录 <span>{{ conversationTotal }}</span></button
        >
      </div>
      <div
        id="conversation-history"
        :class="['conversation-history', { 'is-expanded': historyOpen }]"
      >
        <p class="conversation-caption"
          >最近会话 <span>{{ conversationTotal }}</span></p
        >
        <ul class="conversation-list">
          <li
            v-for="item in conversations"
            :key="item.id"
          >
            <RouterLink
              :to="`/chat/${item.id}`"
              :aria-disabled="busy"
              @click="busy && $event.preventDefault()"
            >
              {{ item.title }}
            </RouterLink>
          </li>
        </ul>
        <p
          v-if="!conversations.length"
          class="sidebar-empty"
          >还没有会话。<br />从你的第一个问题开始。</p
        >
        <div class="pagination">
          <button
            class="secondary"
            :disabled="busy || conversationPage === 1"
            @click="moveSidebar(-1)"
          >
            上一页
          </button>
          <button
            class="secondary"
            :disabled="busy || conversationPage * pageSizes.default >= conversationTotal"
            @click="moveSidebar(1)"
          >
            下一页
          </button>
        </div>
      </div>
    </aside>
    <section class="chat-main card">
      <header class="chat-header">
        <div
          ><p class="eyebrow">KNOWLEDGE ASSISTANT</p><h2>{{ current?.title ?? '新的知识探索' }}</h2></div
        >
        <span
          v-if="!current"
          class="chat-evidence-label"
          ><AppIcon name="lock" />基于授权知识回答</span
        >
        <details v-if="current && !busy">
          <summary>会话管理</summary>
          <div class="chat-title-edit">
            <input
              v-model="title"
              aria-label="会话标题"
              maxlength="100"
            />
            <button
              class="secondary"
              @click="rename"
            >
              保存标题
            </button>
            <button
              class="secondary"
              @click="confirmingDelete = true"
            >
              删除会话
            </button>
          </div>
          <p v-if="confirmingDelete">
            确认删除这个会话？ <button @click="remove"> 确认删除 </button>
            <button
              class="secondary"
              @click="confirmingDelete = false"
            >
              保留
            </button>
          </p>
        </details>
      </header>
      <fieldset
        class="kb-selector"
        :disabled="busy"
      >
        <legend>回答范围 · 选择知识库</legend>
        <label
          v-for="kb in knowledgeBases"
          :key="kb.id"
          ><input
            v-model="selected"
            type="checkbox"
            :value="kb.id"
          />{{ kb.name }}</label
        >
        <p
          v-if="!knowledgeBases.length"
          class="hint"
        >
          尚无可读取的知识库，请先创建并上传文档。
        </p>
      </fieldset>
      <p
        v-if="error"
        class="notice error"
        role="alert"
      >
        {{ error }}
      </p>
      <div
        ref="messageList"
        class="message-list"
        tabindex="0"
        aria-label="会话消息"
        aria-live="polite"
      >
        <RouterLink
          v-if="!messages.length"
          class="chat-empty-link"
          to="/knowledge-bases"
          >管理知识库 <AppIcon name="arrow"
        /></RouterLink>
        <div
          v-if="!messages.length"
          class="chat-empty"
        >
          <span class="assistant-emblem"><AppIcon name="spark" /></span>
          <p class="eyebrow">FROM KNOWLEDGE TO CLARITY</p>
          <h3>让知识，成为答案。</h3>
          <p>选择知识库，提问已索引文档中的内容。<br />关键内容，请结合引用核对。</p>
          <div class="question-suggestions">
            <button
              v-for="suggestion in questionSuggestions"
              :key="suggestion.title"
              type="button"
              class="secondary"
              :disabled="busy"
              @click="useSuggestion(suggestion.question)"
              ><AppIcon :name="suggestion.icon" /><span>{{ suggestion.title }}</span
              ><AppIcon name="arrow"
            /></button>
          </div>
        </div>
        <article
          v-for="message in messages"
          :key="message.id"
          :class="['chat-message', message.role.toLowerCase()]"
        >
          <strong>{{ message.role === messageRole.user ? '你' : '知识库助手' }}</strong>
          <p
            v-if="message.role === messageRole.user"
            class="user-text"
          >
            {{ message.content }}
          </p>
          <MarkdownContent
            v-else
            :content="message.content"
            :citation-ids="message.citations.map(item => item.citationId)"
            @citation="id => openCitation(message, id)"
          />
          <div
            v-if="message.citations.length"
            class="citation-list"
          >
            <button
              v-for="source in message.citations"
              :key="source.citationId"
              class="secondary"
              :disabled="message.status === messageStatus.generating || message.id < 1"
              @click="openCitation(message, source.citationId)"
            >
              [{{ source.citationId }}] {{ source.documentName }} · v{{ source.documentVersion }}
            </button>
          </div>
          <p
            v-if="message.role === messageRole.assistant"
            class="hint"
          >
            {{ { GENERATING: '生成中', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消' }[message.status] }}
            <span v-if="message.errorCode"> · {{ message.errorCode }}</span>
            <span v-if="message.inputTokens !== null || message.outputTokens !== null">
              · 输入 {{ message.inputTokens ?? '未知' }} / 输出 {{ message.outputTokens ?? '未知' }} tokens</span
            >
          </p>
          <VerificationNotice
            v-if="message.role === messageRole.assistant"
            :status="message.faithfulnessStatus"
            :confidence="message.confidenceLevel"
            :generating="message.status === messageStatus.generating"
            :claims="message.verificationDetails?.unsupportedClaims"
          />
          <button
            v-if="
              auth.isAdmin &&
              message.role === messageRole.assistant &&
              message.requestId &&
              message.status !== messageStatus.generating
            "
            type="button"
            class="secondary trace-button"
            @click="openTrace(message)"
            >查看本次检索追踪</button
          >
          <p
            v-if="message.role === messageRole.assistant && message.status !== messageStatus.generating"
            class="hint"
          >
            本次模型总成本：{{
              message.estimatedCost == null ? '不可完整估算' : `¥ ${Number(message.estimatedCost).toFixed(6)}`
            }}
          </p>
        </article>
      </div>
      <form
        class="chat-composer"
        @submit.prevent="send"
      >
        <div class="composer-label"
          ><label for="chat-question">你的问题</label><span>清晰提问，获得更有用的答案</span></div
        >
        <textarea
          id="chat-question"
          ref="questionInput"
          v-model="question"
          rows="3"
          maxlength="4000"
          :disabled="busy"
          placeholder="例如：报销需要哪些材料？"
          @keydown="handleComposerKey"
        />
        <div class="chat-actions">
          <span role="status">{{ status }}</span>
          <span class="composer-shortcut">Ctrl / ⌘ + Enter 发送</span>
          <button
            v-if="busy || messages.some(row => row.status === messageStatus.generating)"
            type="button"
            class="secondary"
            @click="stop"
          >
            停止生成
          </button>
          <button
            type="submit"
            :disabled="!canSend"
          >
            发送 <AppIcon name="arrow" />
          </button>
        </div>
      </form>
      <p class="chat-footnote">AI 回答可能存在偏差，重要信息请结合引用原文核对。</p>
    </section>
    <ResultDialog
      :open="traceDialogOpen"
      title="本次检索追踪"
      :busy="traceLoading"
      :error="traceError"
      loading-text="正在读取本次聊天的检索追踪…"
      @close="closeTrace"
    >
      <RetrievalTraceContent :trace="trace" />
    </ResultDialog>
    <dialog
      ref="citationDialog"
      class="citation-dialog"
      aria-labelledby="citation-dialog-title"
      @cancel.prevent="closeCitation"
      @click="handleCitationBackdrop"
    >
      <header class="citation-dialog-header">
        <h3 id="citation-dialog-title">引用溯源</h3>
        <button
          type="button"
          class="secondary icon-button"
          aria-label="关闭引用"
          autofocus
          @click="closeCitation"
          ><AppIcon name="close"
        /></button>
      </header>
      <div
        class="citation-dialog-body"
        :aria-busy="citationLoading"
      >
        <p
          v-if="citationLoading"
          role="status"
          >正在读取授权原文…</p
        >
        <p
          v-if="citationError"
          class="notice error"
          role="alert"
        >
          {{ citationError }}
        </p>
        <template v-if="citation">
          <h3>[{{ citation.citationId }}] {{ citation.documentName }} · v{{ citation.documentVersion }}</h3>
          <p
            >{{ citation.page ? `第 ${citation.page} 页` : '' }} {{ citation.section }}
            {{ citation.lineStart ? `行 ${citation.lineStart}–${citation.lineEnd ?? citation.lineStart}` : '' }}</p
          >
          <CitationContent :citation="citation" />
          <ul
            v-if="citationSource?.locations.length"
            class="hint"
          >
            <li
              v-for="(location, index) in citationSource.locations"
              :key="index"
            >
              {{ location.page ? `第 ${location.page} 页` : '' }}
              {{ location.chapter }} {{ location.section }} {{ location.sheetName }}
              {{ location.rowStart ? `原表行 ${location.rowStart}–${location.rowEnd ?? location.rowStart}` : '' }}
              {{ location.lineStart ? `原文行 ${location.lineStart}–${location.lineEnd ?? location.lineStart}` : '' }}
            </li>
          </ul>
          <p
            v-if="citationSource && !citationSource.fullEvidence"
            class="hint"
            >历史引用仅保留当时的片段快照。</p
          >
        </template>
      </div>
      <footer class="citation-dialog-actions">
        <template v-if="citation">
          <button
            v-if="citationSource?.format === documentFormat.pdf"
            type="button"
            class="secondary"
            @click="openPdf"
          >
            打开 PDF 原文{{ citation.page ? `第 ${citation.page} 页` : '' }}
          </button>
          <button
            type="button"
            class="secondary"
            @click="download"
          >
            下载引用版本原文件
          </button>
        </template>
        <button
          type="button"
          class="secondary"
          @click="closeCitation"
        >
          关闭
        </button>
      </footer>
    </dialog>
  </main>
</template>
