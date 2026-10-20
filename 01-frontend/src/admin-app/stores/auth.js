import { defineStore } from 'pinia'

/**
 * 管理端登录状态（需求 3(14)：前端实时展示当前登录状态，退出后清空本地身份凭证）
 */
export const useAuthStore = defineStore('adminAuth', {
  state: () => ({
    token: localStorage.getItem('admin_token') || '',
    userInfo: JSON.parse(localStorage.getItem('admin_user_info') || 'null')
  }),
  actions: {
    setLogin({ access_token, user_info }) {
      this.token = access_token
      this.userInfo = user_info || null
      localStorage.setItem('admin_token', access_token)
      localStorage.setItem('admin_user_info', JSON.stringify(this.userInfo))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_user_info')
    }
  }
})
