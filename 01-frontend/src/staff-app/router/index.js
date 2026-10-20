import { useAuthStore } from '../stores/auth'

/** 部门端路由（由根路由统一挂载 /staff 前缀；街道办端已独立为 street-app） */
const routes = [
  { path: '/login', name: 'staff-login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'staff-register', component: () => import('../views/Register.vue'), meta: { public: true } },
  { path: '/forgot', name: 'staff-forgot', component: () => import('../views/ForgotPassword.vue'), meta: { public: true } },
  { path: '/', name: 'staff-home', component: () => import('../views/Home.vue') },
  { path: '/orders', name: 'staff-orders', component: () => import('../views/Orders.vue') },
  { path: '/logs', name: 'staff-logs', component: () => import('../views/AuditLog.vue') },
  { path: '/warnings', name: 'staff-warnings', component: () => import('../views/StaffWarnings.vue') },
  { path: '/notices', name: 'staff-notices', component: () => import('../views/Notices.vue') },
  // 消息中心已下线（页面 StaffMessages.vue 与后端接口保留，仅不再对外展示；恢复时取消注释即可）
  // { path: '/messages', name: 'staff-messages', component: () => import('../views/StaffMessages.vue') },
  { path: '/chat', name: 'staff-chat', component: () => import('../views/Chat.vue') }
]

/** 部门端允许的 user_type（与后端 AuthInterceptor /staff/** 白名单一致，不含 STREET） */
const DEPT_TYPES = ['URBAN', 'PROPERTY', 'LIVELIHOOD', 'TRAFFIC', 'MARKET', 'ENVIRONMENT', 'HOUSING', 'LABOR', 'PUBLIC', 'HEALTH']

/**
 * 部门/街道办登录状态守卫：
 * - 未登录访问受保护页 → 跳对应入口登录页
 * - 已登录访问公开页（登录/注册/忘记密码）→ 仅弹回「本端」工作台；若发现残留的是「他端」token，
 *   先清除、再干净展示本端登录页 —— 从机制上封死跨端推人（部门/街道办互跳的唯一出口）
 * - 已登录但身份与路径不匹配（部门账号进 /street、街道办账号进 /staff）→ 清本端登录态跳本端登录页
 */
export function staffGuard(to) {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    return { path: '/staff/login', query: { redirect: to.fullPath } }
  }
  // 已登录访问公开页：token 属于本端 → 弹回本端工作台；token 属于他端（残留另一端的卡）
  // → 清除该残留登录态后停留本端登录页，绝不按身份把他送去另一端界面
  if (to.meta.public && auth.token) {
    return '/staff'
  }
  return true
}

export default routes
