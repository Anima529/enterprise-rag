<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ApiRequestError } from '../api/http'
import { safeRedirect } from '../router/redirect'
import AppIcon from '../components/AppIcon.vue'
import KnowledgeOrbit from '../components/KnowledgeOrbit.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

/** 根据登录跳转原因显示会话过期或服务暂不可用提示。 */
const notice = computed(() => {
  if (route.query.reason === 'expired') {
    return '登录已过期，请重新登录。'
  }
  if (route.query.reason === 'unavailable') {
    return '暂时无法验证登录状态，请检查服务后重试。'
  }
  return null
})

/** 提交登录表单并跳转到经过安全校验的站内地址。 */
async function submit() {
  busy.value = true
  error.value = null
  try {
    await auth.login({ username: username.value.trim(), password: password.value })
    password.value = ''
    await router.replace(safeRedirect(route.query.redirect))
  } catch (cause) {
    password.value = ''
    error.value = cause instanceof ApiRequestError ? cause.message : '登录失败，请重试。'
  } finally {
    busy.value = false
  }
}

/** 重试会话恢复并按恢复结果更新页面。 */
async function retry() {
  busy.value = true
  error.value = null
  try {
    await auth.restore()
    if (auth.authenticated) {
      await router.replace(safeRedirect(route.query.redirect))
    } else {
      error.value = '登录已失效，请输入账号和密码。'
    }
  } catch {
    error.value = '服务仍不可用，请稍后重试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-story">
      <RouterLink
        class="brand"
        to="/"
      >
        <span class="brand-symbol"><AppIcon name="brand" /></span>
        <span>Enterprise RAG<small>企业知识工作空间</small></span>
      </RouterLink>
      <div class="login-story-content">
        <p class="eyebrow">KNOWLEDGE AT WORK</p>
        <h2>让每一份知识，<br />都有用武之地。</h2>
        <p>从分散的文档，到有据可循的答案。<br />让经验连接彼此，让工作从容向前。</p>
        <KnowledgeOrbit />
      </div>
      <div class="login-story-footer">
        <span>01 / 连接知识</span><span>02 / 获取答案</span><span>03 / 核对依据</span>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-content">
        <p class="eyebrow">YOUR KNOWLEDGE WORKSPACE</p>
        <h1>欢迎回来。</h1>
        <p class="subtitle">使用企业账号，进入你的知识工作空间。</p>
        <p
          v-if="notice"
          class="notice"
          role="status"
        >
          {{ notice }}
        </p>
        <form
          class="login-form"
          @submit.prevent="submit"
        >
          <label for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            name="username"
            autocomplete="username"
            required
            maxlength="64"
            placeholder="请输入企业用户名"
            :disabled="busy"
          />
          <label for="password">密码</label>
          <input
            id="password"
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            required
            maxlength="72"
            placeholder="请输入密码"
            :disabled="busy"
          />
          <p
            v-if="error"
            class="notice error"
            role="alert"
          >
            {{ error }}
          </p>
          <button
            type="submit"
            class="login-submit"
            :disabled="busy"
          >
            <span>{{ busy ? '验证中…' : '登录' }}</span
            ><AppIcon name="arrow" />
          </button>
          <button
            v-if="route.query.reason === 'unavailable' && auth.token"
            type="button"
            class="secondary"
            :disabled="busy"
            @click="retry"
          >
            重试恢复登录
          </button>
        </form>
        <p class="login-security"><AppIcon name="lock" />仅访问当前账号已获授权的企业知识</p>
        <RouterLink
          class="login-status-link"
          to="/system"
          >查看系统运行状态 <AppIcon name="arrow"
        /></RouterLink>
      </div>
      <footer class="login-footer">ENTERPRISE RAG <span>知识相连，工作向前。</span></footer>
    </section>
  </main>
</template>
