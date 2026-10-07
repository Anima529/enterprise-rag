import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { onUnauthorized } from '../api/http'
import { safeRedirect } from './redirect'

/** 系统状态页按需加载，避免登录首屏下载非当前页面代码。 */
const SystemView = () => import('../views/SystemView.vue')

/** 未知路由命中后再加载错误页面。 */
const NotFoundView = () => import('../views/NotFoundView.vue')

/** 登录页面作为独立资源加载，保持已有会话守卫。 */
const LoginView = () => import('../views/LoginView.vue')

/** 员工进入账号工作台时加载首页内容。 */
const ProfileView = () => import('../views/ProfileView.vue')

/** 管理员进入人员目录时加载共用的目录页面。 */
const AdminDirectoryView = () => import('../views/AdminDirectoryView.vue')

/** 权限守卫拒绝访问后加载受限提示页面。 */
const ForbiddenView = () => import('../views/ForbiddenView.vue')

/** 知识库列表在进入对应员工或管理入口时加载。 */
const KnowledgeBaseListView = () => import('../views/KnowledgeBaseListView.vue')

/** 知识库详情及文档管理只在访问详情时加载。 */
const KnowledgeBaseDetailView = () => import('../views/KnowledgeBaseDetailView.vue')

/** 问答页面和 Markdown 渲染在进入聊天时加载。 */
const ChatView = () => import('../views/ChatView.vue')

/** 检索诊断页面只在管理员使用诊断入口时加载。 */
const RetrievalDebugView = () => import('../views/RetrievalDebugView.vue')

/** 用量页面按需加载，员工和管理员继续共用相同组件。 */
const UsageView = () => import('../views/UsageView.vue')

/** 评估页面在权限守卫通过并进入评估入口时加载。 */
const EvaluationView = () => import('../views/EvaluationView.vue')

/** 管理员日志页面在权限守卫通过后按需加载。 */
const AdminLogsView = () => import('../views/AdminLogsView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/profile' },
    { path: '/login', component: LoginView },
    { path: '/system', component: SystemView },
    { path: '/profile', component: ProfileView, meta: { requiresAuth: true } },
    { path: '/chat/:conversationId?', component: ChatView, meta: { requiresAuth: true } },
    { path: '/usage', component: UsageView, meta: { requiresAuth: true } },
    { path: '/admin/evaluation', component: EvaluationView, meta: { requiresAuth: true, admin: true } },
    {
      path: '/admin/usage',
      component: UsageView,
      props: { management: true },
      meta: { requiresAuth: true, admin: true },
    },
    { path: '/admin/retrieval', component: RetrievalDebugView, meta: { requiresAuth: true, admin: true } },
    { path: '/admin/logs', component: AdminLogsView, meta: { requiresAuth: true, admin: true } },
    { path: '/knowledge-bases', component: KnowledgeBaseListView, meta: { requiresAuth: true } },
    { path: '/knowledge-bases/:id', component: KnowledgeBaseDetailView, meta: { requiresAuth: true } },
    {
      path: '/admin/knowledge-bases',
      component: KnowledgeBaseListView,
      props: { management: true },
      meta: { requiresAuth: true, admin: true },
    },
    {
      path: '/admin/knowledge-bases/:id',
      component: KnowledgeBaseDetailView,
      props: { management: true },
      meta: { requiresAuth: true, admin: true },
    },
    {
      path: '/admin/users',
      component: AdminDirectoryView,
      props: { kind: 'users' },
      meta: { requiresAuth: true, admin: true },
    },
    {
      path: '/admin/departments',
      component: AdminDirectoryView,
      props: { kind: 'departments' },
      meta: { requiresAuth: true, admin: true },
    },
    { path: '/forbidden', component: ForbiddenView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', component: NotFoundView },
  ],
})

/** 恢复最新会话后检查登录与管理员权限，保留安全的登录后跳转。 */
router.beforeEach(async to => {
  const auth = useAuthStore()
  if (to.path !== '/login' || to.query.reason !== 'unavailable') {
    try {
      await auth.restore()
    } catch {
      if (to.meta.requiresAuth || to.path === '/login') {
        return { path: '/login', query: { redirect: to.fullPath, reason: 'unavailable' } }
      }
    }
  }
  if (to.meta.requiresAuth && !auth.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && !auth.isAdmin) {
    return '/forbidden'
  }
  if (to.path === '/login' && auth.authenticated) {
    return safeRedirect(to.query.redirect)
  }
})

/** 受保护页面的会话失效时跳转登录页并保留原路径。 */
onUnauthorized(() => {
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    void router.replace({ path: '/login', query: { redirect: current.fullPath, reason: 'expired' } })
  }
})

export default router
