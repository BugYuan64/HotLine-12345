import { defineStore } from 'pinia'

/**
 * 部门端/街道办端登录状态（需求 3(14)：前端实时展示当前登录状态，退出后清空本地身份凭证）
 * 两类入口分 key 存储：部门走 staff_*，街道办走 street_*，互不覆盖、互不串号；
 * staff_scope 记录当前登录端，logout 仅清理当前端凭证（一端失效不连坐另一端）
 */

/** 读取部门端 token（请求拦截器注入 header 时直读 localStorage，刷新后 token 不丢） */
export const readStaffToken = () => localStorage.getItem('staff_token') || ''

/** 读取街道办端 token（街道办独立请求实例注入 header 用） */
export const readStreetToken = () => localStorage.getItem('street_token') || ''

export const useAuthStore = defineStore('staffAuth', {
  state: () => {
    // 当前登录端：优先已记录的 scope；未记录时按存量 key 推断（兼容升级前登录态，部门端优先）
    const scoped = localStorage.getItem('staff_scope')
    const staffToken = localStorage.getItem('staff_token')
    const streetToken = localStorage.getItem('street_token')
    const kind = scoped || (streetToken && !staffToken ? 'street' : 'staff')
    return {
      kind,
      token: localStorage.getItem(kind + '_token') || '',
      userInfo: JSON.parse(localStorage.getItem(kind + '_user_info') || 'null')
    }
  },
  getters: {
    // 归属类型：STREET 街道办 / URBAN/PROPERTY/LIVELIHOOD 等职能部门
    userType: (state) => state.userInfo?.user_type || '',
    // 区划编码（部门账号=区县级 sys_region.region_code，街道办=街道级；56部门口径）
    regionCode: (state) => state.userInfo?.region_code || ''
  },
  actions: {
    /** 登录落库：scope 区分登录入口（staff=部门端 / street=街道办端），各自独立存储 key */
    setLogin({ access_token, user_info }, scope = 'staff') {
      const kind = scope === 'street' ? 'street' : 'staff'
      this.kind = kind
      this.token = access_token
      this.userInfo = user_info || null
      localStorage.setItem('staff_scope', kind)
      localStorage.setItem(kind + '_token', access_token)
      localStorage.setItem(kind + '_user_info', JSON.stringify(this.userInfo))
    },
    logout() {
      // 仅清理当前登录端凭证，不连坐另一端（部门端/街道办端登录态相互独立）
      const kind = this.kind === 'street' ? 'street' : 'staff'
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('staff_scope')
      localStorage.removeItem(kind + '_token')
      localStorage.removeItem(kind + '_user_info')
    }
  }
})