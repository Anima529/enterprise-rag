<script setup lang="ts">
import { accountRole, aclSubjectType, knowledgeBaseStatus, permission } from '../constants/contracts'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { deleteKnowledgeBase, getAcl, getKnowledgeBase, replaceAcl, updateKnowledgeBase } from '../api/knowledgeBase'
import { ApiRequestError } from '../api/http'
import type { AclEntry, KnowledgeBase } from '../types/knowledgeBase'
import KnowledgeBaseDocuments from '../components/KnowledgeBaseDocuments.vue'

const props = withDefaults(defineProps<{ management?: boolean }>(), { management: false })
const route = useRoute()
const router = useRouter()
const kb = ref<KnowledgeBase | null>(null)
const entries = ref<AclEntry[]>([])
const name = ref('')
const description = ref('')
const status = ref<KnowledgeBase['status']>(knowledgeBaseStatus.enabled)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const removing = ref(false)

/** 根据当前知识库权限决定是否显示写操作。 */
const canWrite = computed(() => kb.value?.permission === permission.write || kb.value?.permission === permission.admin)

/** 根据当前权限决定是否显示知识库管理操作。 */
const canAdmin = computed(() => kb.value?.permission === permission.admin)

/** 按管理入口标志选择详情所属的列表路径。 */
const basePath = computed(() => (props.management ? '/admin/knowledge-bases' : '/knowledge-bases'))
let revision = 0

/** 知识库 ID 或入口变化时加载对应详情和授权。 */
watch(
  () => [route.params['id'], props.management],
  () => {
    removing.value = false
    notice.value = ''
    void load()
  },
  { immediate: true },
)

/** 展示详情操作失败的业务原因，不在提示中附加追踪信息。 */
function failure(cause: unknown) {
  return cause instanceof ApiRequestError ? cause.message : '操作失败，请重试。'
}

/** 校验路由 ID 并读取知识库及可管理的 ACL，忽略迟到响应。 */
async function load() {
  const loadRevision = ++revision
  loading.value = true
  kb.value = null
  entries.value = []
  error.value = ''
  try {
    const id = Number(route.params['id'])
    if (!Number.isSafeInteger(id) || id <= 0) {
      error.value = '知识库 ID 不合法。'
      return
    }
    const result = await getKnowledgeBase(id, props.management)
    if (loadRevision !== revision) {
      return
    }
    kb.value = result
    name.value = result.name
    description.value = result.description
    status.value = result.status
    if (result.permission === permission.admin) {
      const acl = await getAcl(id, props.management)
      if (loadRevision === revision) {
        entries.value = acl
      }
    }
  } catch (cause) {
    if (loadRevision === revision) {
      kb.value = null
      entries.value = []
      error.value = failure(cause)
    }
  } finally {
    if (loadRevision === revision) {
      loading.value = false
    }
  }
}

/** 保存知识库属性后刷新详情。 */
async function save() {
  if (!kb.value) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await updateKnowledgeBase(
      kb.value.id,
      { name: name.value, description: description.value, status: status.value },
      props.management,
    )
    if (!props.management && updated.status === knowledgeBaseStatus.disabled) {
      await router.push(basePath.value)
      return
    }
    await load()
    notice.value = '知识库已保存。'
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}

/** 解析编辑的完整授权集合并提交替换，再读取服务器结果。 */
async function saveAcl() {
  if (!kb.value) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await replaceAcl(kb.value.id, entries.value, props.management)
    await load()
    if (!error.value) {
      notice.value = '授权已保存，下一次访问立即生效。'
    }
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}

/** 删除知识库后返回对应入口列表。 */
async function remove() {
  if (!kb.value) {
    return
  }
  busy.value = true
  try {
    await deleteKnowledgeBase(kb.value.id, props.management)
    await router.push(basePath.value)
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">KNOWLEDGE LIBRARY</p><h1>{{ kb?.name ?? '知识库详情' }}</h1>
      </div>
      <div class="link-row">
        <RouterLink :to="basePath"> 返回列表 </RouterLink
        ><button
          class="secondary"
          :disabled="loading || busy"
          @click="load"
        >
          刷新
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
    <p
      v-if="notice"
      class="notice success"
      role="status"
    >
      {{ notice }}
    </p>
    <p
      v-if="loading"
      class="notice"
      role="status"
    >
      正在加载…
    </p>
    <template v-else-if="kb">
      <section class="card">
        <h2>基本信息</h2>
        <p>{{ kb.description || '暂无描述' }}</p>
        <dl class="kb-details">
          <div
            ><dt>归属部门</dt><dd>{{ kb.departmentName }}（ID {{ kb.departmentId }}）</dd></div
          >
          <div
            ><dt>所有者</dt><dd>{{ kb.ownerUsername }}（ID {{ kb.ownerUserId }}）</dd></div
          >
          <div
            ><dt>状态 / 我的权限</dt
            ><dd>{{ kb.status === knowledgeBaseStatus.enabled ? '启用' : '停用' }} / {{ kb.permission }}</dd></div
          >
        </dl>
        <p class="hint"> 私有知识库；所有者拥有管理权，部门归属不自动授予访问权。 </p>
      </section>
      <KnowledgeBaseDocuments
        v-if="kb.status === knowledgeBaseStatus.enabled"
        :key="kb.id"
        :kb-id="kb.id"
        :can-write="canWrite"
      />
      <form
        v-if="canWrite"
        class="card kb-form"
        @submit.prevent="save"
      >
        <h2>编辑知识库</h2>
        <label for="edit-name">名称</label
        ><input
          id="edit-name"
          v-model="name"
          required
          maxlength="100"
          :disabled="busy"
        />
        <label for="edit-description">描述</label
        ><textarea
          id="edit-description"
          v-model="description"
          maxlength="2000"
          rows="3"
          :disabled="busy"
        />
        <template v-if="canAdmin">
          <label for="edit-status">状态</label>
          <select
            id="edit-status"
            v-model="status"
            :disabled="busy"
          >
            <option :value="knowledgeBaseStatus.enabled"> 启用 </option
            ><option :value="knowledgeBaseStatus.disabled"> 停用 </option>
          </select>
          <p class="hint"> 停用后不再可读或参与检索；系统管理员可在知识库管理页重新启用。 </p>
        </template>
        <button :disabled="busy"> 保存知识库 </button>
      </form>
      <form
        v-if="canAdmin"
        class="card kb-form"
        @submit.prevent="saveAcl"
      >
        <h2>访问授权</h2>
        <p
          >READ 可读取，WRITE 可编辑及上传，ADMIN 可管理授权、停用和删除。USER / DEPARTMENT 填写 ID，ROLE
          选择系统角色。用户或角色授权可跨部门共享。</p
        >
        <div
          v-for="(entry, index) in entries"
          :key="index"
          class="acl-row"
        >
          <label
            >主体类型
            <select
              v-model="entry.subjectType"
              :disabled="busy"
              @change="entry.subjectId = entry.subjectType === aclSubjectType.role ? accountRole.user : ''"
            >
              <option :value="aclSubjectType.user">用户</option
              ><option value="DEPARTMENT">部门</option
              ><option :value="aclSubjectType.role">角色</option>
            </select>
          </label>
          <label
            >主体
            <select
              v-if="entry.subjectType === aclSubjectType.role"
              v-model="entry.subjectId"
              :disabled="busy"
              ><option :value="aclSubjectType.user">普通用户 USER</option
              ><option :value="accountRole.systemAdmin">系统管理员 SYSTEM_ADMIN</option></select
            >
            <input
              v-else
              v-model="entry.subjectId"
              required
              pattern="[1-9][0-9]{0,15}"
              inputmode="numeric"
              :disabled="busy"
              placeholder="用户或部门 ID"
            />
          </label>
          <label
            >权限<select
              v-model="entry.permission"
              :disabled="busy"
              ><option :value="permission.read">READ</option
              ><option :value="permission.write">WRITE</option
              ><option :value="permission.admin">ADMIN</option></select
            ></label
          >
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            :aria-label="`移除第 ${index + 1} 条授权`"
            @click="entries.splice(index, 1)"
          >
            移除
          </button>
        </div>
        <p
          v-if="!entries.length"
          class="hint"
        >
          没有显式授权，仅所有者和系统管理员可访问。
        </p>
        <div class="link-row">
          <button
            type="button"
            class="secondary"
            :disabled="busy || entries.length >= 100"
            @click="entries.push({ subjectType: aclSubjectType.user, subjectId: '', permission: permission.read })"
          >
            添加授权
          </button>
          <button :disabled="busy"> 保存授权 </button>
        </div>
        <p class="hint"> 保存会替换全部显式授权。移除的授权立即撤销；所有者和系统管理员权限保留。 </p>
      </form>
      <section
        v-if="canAdmin"
        class="card danger-zone"
      >
        <h2>删除知识库</h2><p>删除后列表、详情和检索均无法访问。</p>
        <button
          v-if="!removing"
          class="danger"
          :disabled="busy"
          @click="removing = true"
        >
          删除知识库
        </button>
        <div
          v-else
          class="link-row"
        >
          <span>确认删除“{{ kb.name }}”？</span
          ><button
            class="danger"
            :disabled="busy"
            @click="remove"
          >
            确认删除 </button
          ><button
            class="secondary"
            :disabled="busy"
            @click="removing = false"
          >
            取消
          </button>
        </div>
      </section>
    </template>
  </main>
</template>
