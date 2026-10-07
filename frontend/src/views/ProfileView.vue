<script setup lang="ts">
import { accountStatus } from '../constants/contracts'
import { onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { me } from '../api/auth'
import { ApiRequestError } from '../api/http'
import AppIcon from '../components/AppIcon.vue'
import KnowledgeOrbit from '../components/KnowledgeOrbit.vue'

const auth = useAuthStore()
const loading = ref(false)
const error = ref<string | null>(null)

/** 进入个人页时查询当前用户的最新信息。 */
onMounted(() => {
  void refresh()
})

/** 刷新公开用户信息并展示可追踪的失败提示。 */
async function refresh() {
  loading.value = true
  error.value = null
  const requestToken = auth.token
  try {
    const result = await me()
    if (requestToken === auth.token && auth.token) {
      auth.user = result
    }
  } catch (cause) {
    error.value = cause instanceof ApiRequestError ? cause.message : '无法获取账号信息。'
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <main class="shell profile-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">YOUR WORKSPACE</p><h1>我的账号</h1><p class="subtitle">从这里，开始今天的知识工作。</p>
      </div>
      <button
        :disabled="loading"
        @click="refresh"
      >
        {{ loading ? '刷新中…' : '刷新账号' }}
      </button>
    </header>
    <p
      v-if="error"
      class="notice error"
      role="alert"
    >
      {{ error }}
    </p>
    <section class="workspace-welcome">
      <div>
        <span class="welcome-kicker"><span class="accent-dot" />让知识流动起来</span>
        <h2>你好，{{ auth.user?.username }}。<br />你的下一个答案，就在这里。</h2>
        <p>查找制度、梳理经验、核对细节。<br />从一个问题开始，让企业知识为你所用。</p>
        <RouterLink
          class="button-link"
          to="/chat"
          >开始知识库问答 <AppIcon name="arrow"
        /></RouterLink>
      </div>
      <KnowledgeOrbit />
    </section>
    <div class="section-heading"><h2>常用工作入口</h2><span>让每一步更简单</span></div>
    <section
      class="workspace-shortcuts"
      aria-label="常用工作入口"
    >
      <RouterLink to="/chat">
        <span class="shortcut-icon"><AppIcon name="chat" /></span>
        <h3>知识库问答</h3><p>用自然语言提问，查看回答依据。</p><AppIcon name="arrow" />
      </RouterLink>
      <RouterLink to="/knowledge-bases">
        <span class="shortcut-icon"><AppIcon name="library" /></span>
        <h3>我的知识库</h3><p>整理文档，维护团队的共同知识。</p><AppIcon name="arrow" />
      </RouterLink>
      <RouterLink to="/usage">
        <span class="shortcut-icon"><AppIcon name="chart" /></span>
        <h3>我的 AI 用量</h3><p>查看调用记录与已估算的成本。</p><AppIcon name="arrow" />
      </RouterLink>
    </section>
    <section
      v-if="auth.user"
      class="card details"
    >
      <div class="section-heading"
        ><h2>账号信息</h2><span>{{ auth.user.username }}</span></div
      >
      <dl>
        <div
          ><dt>所属部门</dt><dd>{{ auth.user.departmentName }}</dd></div
        >
        <div
          ><dt>角色</dt><dd>{{ auth.isAdmin ? '系统管理员' : '普通用户' }}</dd></div
        >
        <div
          ><dt>账号状态</dt><dd>{{ auth.user.status === accountStatus.active ? '正常' : '已停用' }}</dd></div
        >
      </dl>
    </section>
    <section
      v-if="auth.isAdmin"
      class="notice details"
    >
      <h2>人员目录</h2>
      <p class="link-row">
        <RouterLink to="/admin/users"> 查看用户 </RouterLink><RouterLink to="/admin/departments"> 查看部门 </RouterLink>
      </p>
    </section>
  </main>
</template>
