import { useAuthStore } from '../stores/auth'

/** 街道办端路由（由根路由统一挂载 /street 前缀，独立于部门端 /staff） */
const routes = [
  { path: '/login', name: 'street-login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'street-register', component: () => import('../views/Register.vue'), meta: { public: true } },
  { path: '/forgot', name: 'street-forgot', component: () => import('../views/ForgotPassword.vue'), meta: { public: true } },
  { path: '/', name: 'street-home', component: () => import('../views/Home.vue') },
  { path: '/orders', name: 'street-orders', component: () => import('../views/Orders.vue') },
  { path: '/logs', name: 'street-logs', component: () => import('../views/AuditLog.vue') },
  { path: '/warnings', name: 'street-warnings', component: () => import('../views/Warnings.vue') },
  { path: '/notices', name: 'street-notices', component: () => import('../views/Notices.vue') },
  { path: '/chat', name: 'street-chat', component: () => import('../views/Chat.vue') }
]

/** 街道办端登录状态守卫（与其他端凭证完全隔离） */
export function streetGuard(to) {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    return { path: '/street/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && auth.token) {
    return '/street'
  }
  return true
}

export default routes
