<script setup lang="ts">
import { accountRole, accountStatus } from '../constants/contracts'
import { pageSizes } from '../config/client'
import { computed, ref, watch } from 'vue'
import { listDepartments, listUsers } from '../api/admin'
import { ApiRequestError } from '../api/http'
import type { Department, User } from '../types/auth'

const props = defineProps<{ kind: 'users' | 'departments' }>()
const users = ref<User[]>([])
const departments = ref<Department[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

/** 按目录类型选择用户或部门页标题。 */
const title = computed(() => (props.kind === 'users' ? '用户目录' : '部门目录'))

/** 目录类型变化后重置页码并加载对应数据。 */
watch(
  () => props.kind,
  () => {
    page.value = 1
    void load()
  },
  { immediate: true },
)

/** 按目录类型读取管理员分页数据并忽略迟到结果。 */
async function load() {
  loading.value = true
  error.value = null
  try {
    if (props.kind === 'users') {
      const userPage = await listUsers(page.value)
      users.value = userPage.records
      total.value = userPage.total
    } else {
      const departmentPage = await listDepartments(page.value)
      departments.value = departmentPage.records
      total.value = departmentPage.total
    }
  } catch (cause) {
    error.value = cause instanceof ApiRequestError ? cause.message : '加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

/** 切换目录页码并重新查询。 */
function changePage(delta: number) {
  page.value += delta
  void load()
}
</script>
<template>
  <main class="shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">PEOPLE & ORGANIZATION</p><h1>{{ title }}</h1
        ><p class="subtitle"> 共 {{ total }} 条记录 </p> </div
      ><button
        :disabled="loading"
        @click="load"
      >
        刷新
      </button>
    </header>
    <p
      v-if="error"
      class="notice error"
      role="alert"
    >
      {{ error }}
    </p>
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
      <table v-if="props.kind === 'users'">
        <thead
          ><tr><th>ID</th><th>用户名</th><th>部门</th><th>角色</th><th>状态</th></tr></thead
        >
        <tbody>
          <tr
            v-for="item in users"
            :key="item.id"
          >
            <td>{{ item.id }}</td
            ><td>{{ item.username }}</td
            ><td>{{ item.departmentName }}</td
            ><td>{{ item.roles.includes(accountRole.systemAdmin) ? '系统管理员' : '普通用户' }}</td
            ><td>{{ item.status === accountStatus.active ? '正常' : '已停用' }}</td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead
          ><tr><th>ID</th><th>部门代码</th><th>名称</th><th>状态</th></tr></thead
        >
        <tbody>
          <tr
            v-for="item in departments"
            :key="item.id"
          >
            <td>{{ item.id }}</td
            ><td>{{ item.code }}</td
            ><td>{{ item.name }}</td
            ><td>{{ item.status === accountStatus.active ? '正常' : '已停用' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="total === 0"> 暂无记录。 </p>
    </section>
    <div class="pagination">
      <button
        class="secondary"
        :disabled="loading || page === 1"
        @click="changePage(-1)"
      >
        上一页 </button
      ><span>第 {{ page }} 页</span
      ><button
        class="secondary"
        :disabled="loading || page * pageSizes.default >= total"
        @click="changePage(1)"
      >
        下一页
      </button>
    </div>
  </main>
</template>
