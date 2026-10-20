/**
 * 市民端路由（默认入口，挂载于根路径）
 * - /home            首页（与 / 等价，Tab 入口）
 * - /submit-appeal   提交诉求（与 /complaint 等价，Tab 入口）
 * - /progress        办理进度（Tab 入口）
 * - /my              我的个人中心（Tab 入口）
 * - /my-appeals      我的诉求列表（分页）
 * - /notices         通知公告列表（分页）
 * - /messages        我的消息（顶部铃铛入口，二级页面）
 * - /login, /register 登录/注册
 */
const routes = [
  { path: '/login',           name: 'citizen-login',      component: () => import('../views/Login.vue'),     meta: { public: true } },
  { path: '/register',        name: 'citizen-register',   component: () => import('../views/Register.vue'),  meta: { public: true } },

  { path: '/',                name: 'citizen-home',       component: () => import('../views/Home.vue'),      meta: { tab: 'home' } },
  { path: '/home',            name: 'citizen-home-tab',   component: () => import('../views/Home.vue'),      meta: { tab: 'home' } },

  { path: '/complaint',       name: 'citizen-complaint',  component: () => import('../views/Complaint.vue'), meta: { tab: 'submit-appeal' } },
  { path: '/submit-appeal',   name: 'citizen-submit-tab', component: () => import('../views/Complaint.vue'), meta: { tab: 'submit-appeal' } },

  { path: '/progress',        name: 'citizen-progress',   redirect: (to) => ({ path: '/my-appeals', query: to.query }), meta: { tab: 'progress' } },

  { path: '/my',              name: 'citizen-my',         component: () => import('../views/My.vue'),        meta: { tab: 'my' } },

  { path: '/my-appeals',      name: 'citizen-my-appeals', component: () => import('../views/MyAppeals.vue') },

  { path: '/notices',         name: 'citizen-notices',    component: () => import('../views/Notices.vue') },

  { path: '/messages',        name: 'citizen-messages',   component: () => import('../views/Messages.vue') },

  { path: '/chat',            name: 'citizen-chat',       component: () => import('../views/Chat.vue') }
]

/**
 * 读取市民端登录凭证（localStorage 优先，避免刷新后丢失）
 * - 路由守卫在导航最早期触发，此时不依赖 Pinia 状态；
 *   直接读 localStorage 是最可靠的判断依据。
 */
export function readCitizenToken() {
  try {
    const raw = localStorage.getItem('citizen_token')
    if (raw && raw.trim()) return raw.trim()
  } catch (_) { /* 忽略读取异常 */ }
  return ''
}

/**
 * 市民端登录状态守卫
 * - 已登录（token 存在）→ 访问 public 页面（/login、/register）时回首页
 * - 未登录（无 token）   → 访问非 public 页面时跳 /login，并保留 redirect
 */
export function citizenGuard(to) {
  const token = readCitizenToken()
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && token) {
    return '/home'
  }
  return true
}

export default routes