<script setup lang="ts">
import { knowledgeBaseStatus, permission } from '../constants/contracts'
import { pageSizes } from '../config/client'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createKnowledgeBase, listKnowledgeBases } from '../api/knowledgeBase'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { KnowledgeBase } from '../types/knowledgeBase'
import AppIcon from '../components/AppIcon.vue'

const props = withDefaults(defineProps<{ management?: boolean }>(), { management: false })
const auth = useAuthStore()
const router = useRouter()
const records = ref<KnowledgeBase[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const creating = ref(false)
const error = ref('')
const showCreate = ref(false)
const name = ref('')
const description = ref('')
const departmentId = ref(auth.user?.departmentId ?? 0)
const ownerUserId = ref<number | undefined>()
const permissionLabels = {
  [permission.read]: '可读取',
  [permission.write]: '可编辑',
  [permission.admin]: '可管理',
}

/** 按管理标志选择知识库列表入口路径。 */
const basePath = computed(() => (props.management ? '/admin/knowledge-bases' : '/knowledge-bases'))
let revision = 0

/** 入口模式变化时重置分页并刷新可见知识库。 */
watch(
  () => props.management,
  () => {
    page.value = 1
    showCreate.value = false
    void load()
  },
  { immediate: true },
)

/** 展示列表操作失败的业务原因，不在提示中附加追踪信息。 */
function failure(cause: unknown) {
  return cause instanceof ApiRequestError ? cause.message : '操作失败，请重试。'
}

/** 按入口和页码刷新知识库，丢弃已失效请求结果。 */
async function load() {
  const loadRevision = ++revision
  loading.value = true
  error.value = ''
  records.value = []
  try {
    const knowledgeBasePage = await listKnowledgeBases(page.value, props.management)
    if (loadRevision !== revision) {
      return
    }
    records.value = knowledgeBasePage.records
    total.value = knowledgeBasePage.total
  } catch (cause) {
    if (loadRevision === revision) {
      error.value = failure(cause)
    }
  } finally {
    if (loadRevision === revision) {
      loading.value = false
    }
  }
}

/** 创建知识库后刷新列表并清理创建表单。 */
async function create() {
  if (!auth.user) {
    return
  }
  creating.value = true
  error.value = ''
  try {
    const result = await createKnowledgeBase({
      name: name.value,
      description: description.value,
      departmentId: auth.isAdmin ? departmentId.value : auth.user.departmentId,
      ...(auth.isAdmin && ownerUserId.value ? { ownerUserId: ownerUserId.value } : {}),
    })
    await router.push(`${basePath.value}/${result.id}`)
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    creating.value = false
  }
}

/** 切换知识库页码并加载列表。 */
function changePage(delta: number) {
  page.value += delta
  void load()
}
</script>

<template>
  <main class="shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">KNOWLEDGE LIBRARY</p>
        <h1>{{ props.management ? '知识库管理' : '我的知识库' }}</h1>
        <p class="subtitle">
          {{
            props.management ? '管理所有知识库，包括已停用的知识库。' : '查看已授权的知识库，或创建自己的私有知识库。'
          }}
          共 {{ total }} 个。
        </p>
      </div>
      <div class="link-row">
        <button
          class="secondary"
          :disabled="loading"
          @click="load"
        >
          刷新
        </button>
        <button
          :aria-expanded="showCreate"
          aria-controls="create-knowledge-base"
          @click="showCreate = !showCreate"
        >
          <AppIcon :name="showCreate ? 'close' : 'plus'" />
          {{ showCreate ? '收起表单' : '创建知识库' }}
        </button>
      </div>
    </header>
    <p
      v-if="error"
      class="notice error"
      role="alert"
    >
      {{ error }}
    </p>
    <form
      v-if="showCreate"
      id="create-knowledge-base"
      class="card kb-form"
      @submit.prevent="create"
    >
      <h2>创建私有知识库</h2>
      <p>创建后所有者拥有管理权；同部门成员需要单独授权。</p>
      <label for="kb-name">名称</label>
      <input
        id="kb-name"
        v-model="name"
        required
        maxlength="100"
        :disabled="creating"
      />
      <label for="kb-description">描述</label>
      <textarea
        id="kb-description"
        v-model="description"
        maxlength="2000"
        rows="3"
        :disabled="creating"
      />
      <template v-if="auth.isAdmin">
        <label for="kb-department">归属部门 ID</label>
        <input
          id="kb-department"
          v-model.number="departmentId"
          type="number"
          min="1"
          max="9007199254740991"
          required
          :disabled="creating"
        />
        <label for="kb-owner">所有者用户 ID（留空为自己）</label>
        <input
          id="kb-owner"
          v-model.number="ownerUserId"
          type="number"
          min="1"
          max="9007199254740991"
          :disabled="creating"
        />
        <p class="hint"> 可在管理员用户与部门目录查询 ID。 </p>
      </template>
      <button :disabled="creating">
        {{ creating ? '创建中…' : '确认创建' }}
      </button>
    </form>
    <p
      v-if="loading"
      class="notice"
      role="status"
    >
      正在加载…
    </p>
    <section
      v-else-if="!error"
      class="card table-scroll"
    >
      <table v-if="records.length">
        <caption class="table-caption"
          >{{ props.management ? '知识库目录' : '已授权知识库' }}<span>点击名称查看文档与详情</span></caption
        >
        <thead
          ><tr><th>名称</th><th>归属部门</th><th>所有者</th><th>状态</th><th>我的权限</th></tr></thead
        >
        <tbody>
          <tr
            v-for="item in records"
            :key="item.id"
          >
            <td>
              <RouterLink :to="`${basePath}/${item.id}`">
                <span class="table-name-icon"><AppIcon name="library" /></span>{{ item.name }}
              </RouterLink>
            </td>
            <td>{{ item.departmentName }}</td
            ><td>{{ item.ownerUsername }}</td>
            <td
              ><span :class="['badge', item.status === knowledgeBaseStatus.enabled ? 'up' : 'pending']">{{
                item.status === knowledgeBaseStatus.enabled ? '启用' : '停用'
              }}</span></td
            ><td
              ><span class="permission-label">{{ permissionLabels[item.permission] }}</span></td
            >
          </tr>
        </tbody>
      </table>
      <div
        v-else
        class="empty-state"
        ><AppIcon name="library" /><h2>知识，从这里积累。</h2><p>暂无知识库。创建一个知识库，或联系所有者授权。</p></div
      >
    </section>
    <div class="pagination">
      <button
        class="secondary"
        :disabled="loading || page === 1"
        @click="changePage(-1)"
      >
        上一页
      </button>
      <span>第 {{ page }} 页</span>
      <button
        class="secondary"
        :disabled="loading || page * pageSizes.default >= total"
        @click="changePage(1)"
      >
        下一页
      </button>
    </div>
  </main>
</template>
