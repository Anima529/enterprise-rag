<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { listAuditLogs, listRequestLogs } from '../api/adminLog'
import { ApiRequestError } from '../api/http'
import ResultDialog from '../components/ResultDialog.vue'
import { pageSizes } from '../config/client'
import {
  ADMIN_LOG_DEFAULT_DAYS,
  ADMIN_LOG_TIME_ZONE,
  MAX_SAFE_USER_ID,
  requestLogStatuses,
} from '../constants/adminLog'
import type { AuditLog, LogKind, LogQuery, RequestLog, RequestLogStatus } from '../types/adminLog'
import { usagePeriodEnding } from '../utils/usageCharts'

const today = new Intl.DateTimeFormat('sv-SE', { timeZone: ADMIN_LOG_TIME_ZONE }).format(new Date())
const initialPeriod = usagePeriodEnding(today, ADMIN_LOG_DEFAULT_DAYS)
const kind = ref<LogKind>('audit')
const from = ref(initialPeriod.from)
const to = ref(initialPeriod.to)
const userId = ref<string | number>('')
const requestId = ref('')
const action = ref('')
const status = ref<RequestLogStatus | ''>('')
const appliedQuery = ref<LogQuery>({ ...initialPeriod })
const auditLogs = ref<AuditLog[]>([])
const requestLogs = ref<RequestLog[]>([])
const selected = ref<AuditLog | RequestLog | null>(null)
const dialogOpen = ref(false)
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
let revision = 0
let alive = true

/** 总页数至少显示一页，便于空结果时保持分页提示稳定。 */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSizes.default)))

/** 首次进入显示北京时间近七天的操作审计。 */
onMounted(load)

/** 离开页面后使请求失效，防止迟到响应更新已卸载的页面。 */
onBeforeUnmount(() => {
  alive = false
  revision++
  closeDetails()
})

/** 使用已提交的筛选查询，切换类别或分页时忽略过期响应。 */
async function load(): Promise<void> {
  const currentRevision = ++revision
  const currentKind = kind.value
  loading.value = true
  error.value = null
  closeDetails()
  try {
    if (currentKind === 'audit') {
      const logs = await listAuditLogs(appliedQuery.value, page.value)
      if (!alive || currentRevision !== revision) {
        return
      }
      auditLogs.value = logs.records
      total.value = logs.total
    } else {
      const logs = await listRequestLogs(appliedQuery.value, page.value)
      if (!alive || currentRevision !== revision) {
        return
      }
      requestLogs.value = logs.records
      total.value = logs.total
    }
  } catch (cause) {
    if (alive && currentRevision === revision) {
      error.value = cause instanceof ApiRequestError ? cause.message : '日志加载失败，请重试。'
    }
  } finally {
    if (alive && currentRevision === revision) {
      loading.value = false
    }
  }
}

/** 校验日期与安全整数用户 ID，将草稿筛选固定后回到第一页。 */
function search(): void {
  const userIdText = String(userId.value).trim()
  const parsedUserId = userIdText ? Number(userIdText) : undefined
  if (!from.value || !to.value || from.value > to.value) {
    error.value = '请选择有效日期范围，开始日期不能晚于结束日期。'
    return
  }
  if (
    parsedUserId !== undefined &&
    (!/^\d+$/.test(userIdText) || !Number.isSafeInteger(parsedUserId) || parsedUserId <= 0)
  ) {
    error.value = '用户 ID 必须为有效的正整数。'
    return
  }
  const query: LogQuery = {
    from: from.value,
    to: to.value,
  }
  if (parsedUserId !== undefined) {
    query.userId = parsedUserId
  }
  if (requestId.value.trim()) {
    query.requestId = requestId.value.trim()
  }
  if (kind.value === 'audit' && action.value.trim()) {
    query.action = action.value.trim()
  }
  if (kind.value === 'requests' && status.value) {
    query.status = status.value
  }
  appliedQuery.value = query
  page.value = 1
  void load()
}

/** 切换日志类别时清除旧表格，保留共用筛选并移除类别专用条件。 */
function changeKind(nextKind: LogKind): void {
  kind.value = nextKind
  auditLogs.value = []
  requestLogs.value = []
  total.value = 0
  const query = { ...appliedQuery.value }
  delete query.action
  delete query.status
  appliedQuery.value = query
  action.value = ''
  status.value = ''
  page.value = 1
  void load()
}

/** 恢复初始时间范围和空筛选后查询第一页。 */
function reset(): void {
  from.value = initialPeriod.from
  to.value = initialPeriod.to
  userId.value = ''
  requestId.value = ''
  action.value = ''
  status.value = ''
  search()
}

/** 分页沿用最后提交的筛选，未提交的输入不会影响结果。 */
function changePage(delta: number): void {
  page.value += delta
  void load()
}

/** 以北京时间展示服务器 UTC 时间，便于与日期筛选核对。 */
function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: ADMIN_LOG_TIME_ZONE, hour12: false })
}

/** 将持久化请求状态映射为中文标签。 */
function statusLabel(value: RequestLogStatus): string {
  return requestLogStatuses.find(entry => entry.value === value)?.label ?? value
}

/** 点击记录后立即在弹窗展示完整追踪元数据，长标识允许复制和换行。 */
function showDetails(log: AuditLog | RequestLog): void {
  selected.value = log
  dialogOpen.value = true
}

/** 关闭详情并清空选中记录，刷新或切换日志后不保留旧内容。 */
function closeDetails(): void {
  dialogOpen.value = false
  selected.value = null
}
</script>

<template>
  <main class="shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">LOGS & TRACING</p>
        <h1>日志查看</h1>
        <p class="subtitle">查看操作审计与 RAG 请求追踪，日期按北京时间查询。</p>
      </div>
      <button
        :disabled="loading"
        @click="load"
        >刷新</button
      >
    </header>

    <div
      class="log-tabs"
      role="group"
      aria-label="日志类型"
    >
      <button
        :class="{ secondary: kind !== 'audit' }"
        :aria-pressed="kind === 'audit'"
        @click="changeKind('audit')"
        >操作审计</button
      >
      <button
        :class="{ secondary: kind !== 'requests' }"
        :aria-pressed="kind === 'requests'"
        @click="changeKind('requests')"
        >RAG 请求</button
      >
    </div>

    <form
      class="card log-filters"
      @submit.prevent="search"
    >
      <label
        >开始日期<input
          v-model="from"
          type="date"
          required
      /></label>
      <label
        >结束日期<input
          v-model="to"
          type="date"
          required
      /></label>
      <label
        >用户 ID<input
          v-model="userId"
          type="number"
          min="1"
          :max="MAX_SAFE_USER_ID"
          step="1"
          placeholder="全部用户"
      /></label>
      <label
        >requestId<input
          v-model="requestId"
          maxlength="120"
          placeholder="精确匹配请求 ID"
      /></label>
      <label v-if="kind === 'audit'"
        >操作<input
          v-model="action"
          maxlength="80"
          placeholder="如 EVALUATION_DATASET_CREATE"
      /></label>
      <label v-else
        >请求状态<select v-model="status">
          <option value="">全部状态</option>
          <option
            v-for="entry in requestLogStatuses"
            :key="entry.value"
            :value="entry.value"
            >{{ entry.label }}</option
          >
        </select></label
      >
      <div class="log-filter-actions">
        <button
          type="submit"
          :disabled="loading"
          >查询</button
        >
        <button
          type="button"
          class="secondary"
          :disabled="loading"
          @click="reset"
          >重置</button
        >
      </div>
    </form>

    <p
      v-if="error"
      class="notice error"
      role="alert"
      >{{ error }}</p
    >
    <p
      v-if="loading"
      class="notice"
      role="status"
      >正在加载日志…</p
    >
    <section
      v-else-if="!error"
      class="card table-scroll"
      aria-label="日志列表"
    >
      <p class="log-summary">共 {{ total }} 条记录，按时间倒序排列。</p>
      <table v-if="kind === 'audit'">
        <thead
          ><tr><th>时间</th><th>操作者</th><th>操作</th><th>资源 ID</th><th>requestId</th><th>详情</th></tr></thead
        >
        <tbody
          ><tr
            v-for="log in auditLogs"
            :key="log.id"
          >
            <td class="log-time">{{ formatTime(log.createdAt) }}</td>
            <td
              >{{ log.username ?? '用户已删除' }}<small>ID: {{ log.userId ?? '—' }}</small></td
            >
            <td class="log-identifier">{{ log.action }}</td
            ><td>{{ log.resourceId ?? '—' }}</td>
            <td class="log-identifier">{{ log.requestId ?? '—' }}</td>
            <td
              ><button
                class="secondary"
                :aria-label="`查看审计 ${log.id} 详情`"
                @click="showDetails(log)"
                >查看</button
              ></td
            >
          </tr></tbody
        >
      </table>
      <table v-else>
        <thead
          ><tr
            ><th>时间</th><th>用户</th><th>状态</th><th>路线</th><th>耗时</th><th>requestId</th><th>详情</th></tr
          ></thead
        >
        <tbody
          ><tr
            v-for="log in requestLogs"
            :key="log.requestId"
          >
            <td class="log-time">{{ formatTime(log.createdAt) }}</td>
            <td
              >{{ log.username ?? '未知用户' }}<small>{{ log.departmentName ?? '—' }} · {{ log.userId }}</small></td
            >
            <td>{{ statusLabel(log.status) }}</td
            ><td>{{ log.route }}</td
            ><td>{{ log.latencyMs }} ms</td>
            <td class="log-identifier">{{ log.requestId }}</td>
            <td
              ><button
                class="secondary"
                :aria-label="`查看请求 ${log.requestId} 详情`"
                @click="showDetails(log)"
                >查看</button
              ></td
            >
          </tr></tbody
        >
      </table>
      <p
        v-if="total === 0"
        class="notice"
        >此范围内暂无日志，可调整筛选条件后查询。</p
      >
    </section>

    <div class="pagination">
      <button
        class="secondary"
        :disabled="loading || !!error || page <= 1"
        @click="changePage(-1)"
        >上一页</button
      >
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <button
        class="secondary"
        :disabled="loading || !!error || page >= totalPages"
        @click="changePage(1)"
        >下一页</button
      >
    </div>

    <ResultDialog
      :open="dialogOpen"
      title="日志详情"
      @close="closeDetails"
    >
      <section
        v-if="selected"
        class="log-details"
        aria-label="日志详情"
        aria-live="polite"
      >
        <dl>
          <dt>requestId</dt><dd>{{ selected.requestId ?? '—' }}</dd>
                                                                     <dt>traceId</dt
          ><dd>{{ selected.traceId ?? '—' }}</dd>
                                                  <dt>记录时间</dt><dd>{{ formatTime(selected.createdAt) }}</dd>
          <template v-if="'action' in selected">
            <dt>审计 ID</dt><dd>{{ selected.id }}</dd>
                                                     <dt>操作</dt><dd>{{ selected.action }}</dd>
                                                                                               <dt>资源 ID</dt
            ><dd>{{ selected.resourceId ?? '未记录' }}</dd>
          </template>
          <template v-else>
            <dt>状态</dt><dd>{{ statusLabel(selected.status) }}</dd>
                                                                   <dt>路线 / 原因码</dt
            ><dd>{{ selected.route }} / {{ selected.reasonCode }}</dd>
                                                                       <dt>总耗时</dt
            ><dd>{{ selected.latencyMs }} ms</dd>
                                                  <dt>首 Token 耗时</dt
            ><dd>{{ selected.firstTokenMs === null ? '未记录' : `${selected.firstTokenMs} ms` }}</dd>
                                                                                                   <dt>缓存命中</dt
            ><dd>{{ selected.cacheHit ? '是' : '否' }}</dd>
                                                          <dt>错误码</dt><dd>{{ selected.errorCode ?? '无' }}</dd>
            <dt>消息 ID</dt><dd>{{ selected.messageId ?? '—' }}</dd>
                                                                   <dt>状态更新时间</dt
            ><dd>{{ formatTime(selected.updatedAt) }}</dd>
          </template>
        </dl>
      </section>
    </ResultDialog>
    <p class="subtitle">操作审计记录成功的知识库、ACL、文档、价格与评估变更；请求日志显示已登记的 RAG 请求。</p>
  </main>
</template>

<style scoped>
.log-tabs,
.log-filter-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.log-filters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}
.log-filters label {
  display: grid;
  gap: var(--space-2);
}
.log-filter-actions {
  align-self: end;
  margin-bottom: 0;
}
.log-time {
  white-space: nowrap;
}
.log-identifier {
  min-width: 160px;
  max-width: 320px;
  overflow-wrap: anywhere;
  font-family: monospace;
}
small {
  display: block;
  color: var(--muted);
}
.log-summary {
  margin-top: 0;
}
.log-details dl {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: var(--space-3);
}
.log-details dt {
  color: var(--muted);
}
.log-details dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 1000px) {
  .log-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .log-filters,
  .log-details dl {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
