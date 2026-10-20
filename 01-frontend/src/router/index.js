import { createRouter, createWebHistory } from 'vue-router'
import citizenRoutes, { citizenGuard } from '../citizen-app/router'
import staffRoutes, { staffGuard } from '../staff-app/router'
import streetRoutes, { streetGuard } from '../street-app/router'
import adminRoutes, { adminGuard } from '../admin-app/router'

/** 子应用路由统一挂载前缀（首页路径 / 特殊处理为前缀本身） */
const withPrefix = (prefix, routes) =>
  routes.map((r) => ({ ...r, path: r.path === '/' ? prefix : prefix + r.path }))

/**
 * 四端合并路由（Vite 多应用架构：单服务、单主入口分发）
 * - /        市民端（默认入口，未登录跳转 /login）
 * - /staff   部门端（未登录跳转 /staff/login）
 * - /street  街道办端（未登录跳转 /street/login，与部门端完全独立）
 * - /admin   管理端（未登录跳转 /admin/login）
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...citizenRoutes,
    ...withPrefix('/staff', staffRoutes),
    ...withPrefix('/street', streetRoutes),
    ...withPrefix('/admin', adminRoutes),
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  if (to.path.startsWith('/admin')) return adminGuard(to)
  if (to.path.startsWith('/street')) return streetGuard(to)
  if (to.path.startsWith('/staff')) return staffGuard(to)
  return citizenGuard(to)
})

export default router
