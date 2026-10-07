<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { ApiRequestError } from './api/http'
import AppIcon, { type IconName } from './components/AppIcon.vue'

interface NavigationItem {
  path: string
  label: string
  icon: IconName
}

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const busy = ref(false)
const error = ref<string | null>(null)
const mobileOpen = ref(false)
const menuButton = ref<HTMLButtonElement | null>(null)
const sidebar = ref<HTMLElement | null>(null)
let mobileMedia: MediaQueryList | undefined

const workspaceLinks: NavigationItem[] = [
  { path: '/chat', label: '知识库问答', icon: 'chat' },
  { path: '/knowledge-bases', label: '知识库', icon: 'library' },
  { path: '/usage', label: '我的用量', icon: 'chart' },
  { path: '/profile', label: '我的账号', icon: 'user' },
]
const managementLinks: NavigationItem[] = [
  { path: '/admin/knowledge-bases', label: '知识库管理', icon: 'library' },
  { path: '/admin/users', label: '用户', icon: 'users' },
  { path: '/admin/departments', label: '部门', icon: 'building' },
  { path: '/admin/usage', label: '用量管理', icon: 'chart' },
  { path: '/admin/evaluation', label: '离线评估', icon: 'flask' },
  { path: '/admin/retrieval', label: '检索调试', icon: 'trace' },
  { path: '/admin/logs', label: '日志查看', icon: 'file' },
]

/** 登录页使用独立布局，其余页面保留统一工作台。 */
const isLogin = computed(() => route.path === '/login')

/** 从现有路由生成位置提示，知识库详情仍归属知识库入口。 */
const pageTitle = computed(() => {
  if (route.path === '/login') {
    return '登录'
  }
  if (route.path === '/forbidden') {
    return '访问受限'
  }
  const links = [...workspaceLinks, ...managementLinks, { path: '/system', label: '系统状态' }]
  return links.find(link => route.path === link.path || route.path.startsWith(`${link.path}/`))?.label ?? '工作空间'
})

/** 路由完成后收起移动导航，并将抽屉内的焦点移到新内容。 */
watch(
  () => route.fullPath,
  async () => {
    const wasOpen = mobileOpen.value
    mobileOpen.value = false
    if (wasOpen) {
      await nextTick()
      document.getElementById('workspace-content')?.focus()
    }
  },
)

/** 位置变化时同步浏览器标题，便于员工在多个标签页之间辨认。 */
watch(
  pageTitle,
  title => {
    document.title = `${title} · Enterprise RAG`
  },
  { immediate: true },
)

/** 监听移动布局退出，避免窗口放大后背景仍处于禁用状态。 */
onMounted(() => {
  mobileMedia = window.matchMedia('(max-width: 760px)')
  mobileMedia.addEventListener('change', handleViewportChange)
})

/** 页面卸载时移除布局监听，避免重复注册。 */
onBeforeUnmount(() => {
  mobileMedia?.removeEventListener('change', handleViewportChange)
})

/** 导航展开后把键盘焦点移入侧栏。 */
async function openMenu() {
  mobileOpen.value = true
  await nextTick()
  sidebar.value?.querySelector<HTMLButtonElement>('.mobile-close')?.focus()
}

/** 关闭移动导航并把焦点归还打开按钮。 */
async function closeMenu() {
  mobileOpen.value = false
  await nextTick()
  menuButton.value?.focus()
}

/** 切回桌面布局时清除移动抽屉状态。 */
function handleViewportChange(event: MediaQueryListEvent) {
  if (!event.matches) {
    mobileOpen.value = false
  }
}

/** 移动导航支持 Escape 和循环 Tab，键盘操作不会进入遮挡内容。 */
function handleNavigationKey(event: KeyboardEvent) {
  if (!mobileOpen.value) {
    return
  }
  if (event.key === 'Escape') {
    closeMenu()
    return
  }
  if (event.key !== 'Tab') {
    return
  }
  const controls = sidebar.value?.querySelectorAll<HTMLElement>('a, button:not(:disabled)')
  const first = controls?.[0]
  const last = controls?.[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

/** 撤销当前会话并返回登录页，失败时保留可重试的状态。 */
async function logout() {
  busy.value = true
  error.value = null
  try {
    await auth.logout()
    await router.replace('/login')
  } catch (cause) {
    error.value = cause instanceof ApiRequestError ? cause.message : '登出失败，请重试。'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <RouterView v-if="isLogin" />
  <div
    v-else
    class="app-workspace"
  >
    <a
      class="skip-link"
      href="#workspace-content"
      >跳到主要内容</a
    >
    <button
      v-if="mobileOpen"
      class="nav-backdrop"
      tabindex="-1"
      aria-label="关闭导航"
      @click="closeMenu"
    />
    <aside
      id="workspace-navigation"
      ref="sidebar"
      :class="['app-sidebar', { 'is-open': mobileOpen }]"
      :role="mobileOpen ? 'dialog' : undefined"
      :aria-modal="mobileOpen ? true : undefined"
      :aria-label="mobileOpen ? '工作空间导航' : undefined"
      @keydown="handleNavigationKey"
    >
      <button
        class="icon-button mobile-close"
        aria-label="关闭导航"
        @click="closeMenu"
        ><AppIcon name="close"
      /></button>
      <RouterLink
        class="brand"
        to="/"
      >
        <span class="brand-symbol"><AppIcon name="brand" /></span>
        <span>Enterprise RAG<small>企业知识工作空间</small></span>
      </RouterLink>
      <nav aria-label="主导航">
        <p class="nav-group-label">工作空间 <span>WORKSPACE</span></p>
        <template v-if="auth.authenticated">
          <RouterLink
            v-for="link in workspaceLinks"
            :key="link.path"
            :to="link.path"
            class="nav-item"
            ><AppIcon :name="link.icon" /><span>{{ link.label }}</span></RouterLink
          >
        </template>
        <RouterLink
          v-else
          to="/login"
          class="nav-item"
          ><AppIcon name="user" /><span>登录</span></RouterLink
        >
        <template v-if="auth.isAdmin">
          <p class="nav-group-label management-label">管理中心 <span>ADMIN</span></p>
          <RouterLink
            v-for="link in managementLinks"
            :key="link.path"
            :to="link.path"
            class="nav-item"
            ><AppIcon :name="link.icon" /><span>{{ link.label }}</span></RouterLink
          >
        </template>
      </nav>
      <div class="sidebar-bottom">
        <RouterLink
          to="/system"
          class="nav-item"
          ><AppIcon name="system" /><span>系统状态</span></RouterLink
        >
        <div
          v-if="auth.authenticated"
          class="sidebar-account"
        >
          <span class="avatar">{{ auth.user?.username.slice(0, 1).toUpperCase() }}</span>
          <RouterLink to="/profile">
            <strong>{{ auth.user?.username }}</strong>
            <small>{{ auth.user?.departmentName }}</small>
          </RouterLink>
          <button
            class="icon-button logout-button"
            :disabled="busy"
            :aria-label="busy ? '退出中…' : '退出登录'"
            :title="busy ? '退出中…' : '退出登录'"
            @click="logout"
            ><AppIcon name="logout"
          /></button>
        </div>
        <p class="sidebar-caption">连接知识 · 看见依据</p>
      </div>
    </aside>
    <div
      class="workspace-body"
      :inert="mobileOpen"
    >
      <header class="workspace-topbar">
        <div class="breadcrumb">
          <button
            ref="menuButton"
            class="icon-button mobile-menu"
            aria-label="打开导航"
            aria-controls="workspace-navigation"
            :aria-expanded="mobileOpen"
            @click="openMenu"
            ><AppIcon name="menu"
          /></button>
          <span class="breadcrumb-root">工作空间</span><span class="breadcrumb-divider">/</span>
          <strong>{{ pageTitle }}</strong>
        </div>
        <span class="workspace-caption"><AppIcon name="lock" />企业内部知识服务</span>
      </header>
      <div
        id="workspace-content"
        class="workspace-content"
        tabindex="-1"
      >
        <p
          v-if="error"
          class="nav-error notice error"
          role="alert"
          >{{ error }}</p
        >
        <RouterView />
      </div>
    </div>
  </div>
</template>
