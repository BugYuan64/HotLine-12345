import { useAuthStore } from '../stores/auth'

/** 管理端路由（由根路由统一挂载 /admin 前缀） */
const routes = [
  { path: '/login', name: 'admin-login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', name: 'admin-dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/classify', name: 'admin-classify', component: () => import('../views/Classify.vue') },
  { path: '/warnings', name: 'admin-warnings', component: () => import('../views/Warnings.vue') },
  { path: '/orders', name: 'admin-orders', component: () => import('../views/WorkOrder.vue') },
  { path: '/final-audit', name: 'admin-final-audit', component: () => import('../views/FinalAudit.vue') },
  { path: '/notices', name: 'admin-notices', component: () => import('../views/NoticeManage.vue') },
  { path: '/stats', name: 'admin-stats', component: () => import('../views/Stats.vue') },
  { path: '/logs', name: 'admin-logs', component: () => import('../views/Logs.vue') },
  { path: '/dept-logs', name: 'admin-dept-logs', component: () => import('../views/DeptLogs.vue') },
  { path: '/street-logs', name: 'admin-street-logs', component: () => import('../views/StreetLogs.vue') },
  { path: '/audits', name: 'admin-audits', component: () => import('../views/AccountAudit.vue') },
  { path: '/users', name: 'admin-users', component: () => import('../views/UserAccount.vue') },
  { path: '/appeals/parent', name: 'admin-appeal-parent', component: () => import('../views/AppealParent.vue') },
  { path: '/appeals/child', name: 'admin-appeal-child', component: () => import('../views/AppealChild.vue') },
  { path: '/chat', name: 'admin-chat', component: () => import('../views/Chat.vue') }
]

/** 管理端登录状态守卫：登录后跳转系统总览（需求 3(12) 差异化跳转） */
export function adminGuard(to) {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && auth.token) {
    return '/admin'
  }
  return true
}

export default routes
