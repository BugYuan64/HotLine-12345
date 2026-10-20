import { defineStore } from 'pinia'

/**
 * 街道办端登录状态（独立应用，与管理端/部门端/市民端同级）：
 * 使用独立的 street_token/street_user_info 本地存储，与其他端凭证互不覆盖。
 */
export const useAuthStore = defineStore('streetAuth', {
  state: () => ({
    token: localStorage.getItem('street_token') || '',
    userInfo: JSON.parse(localStorage.getItem('street_user_info') || 'null')
  }),
  getters: {
    userType: (state) => state.userInfo?.user_type || ''
  },
  actions: {
    setLogin({ access_token, user_info }) {
      this.token = access_token
      this.userInfo = user_info || null
      localStorage.setItem('street_token', access_token)
      localStorage.setItem('street_user_info', JSON.stringify(this.userInfo))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('street_token')
      localStorage.removeItem('street_user_info')
    }
  }
})

/** 直接读取街道办端 token（聊天流式请求头注入用，刷新后不丢） */
export const readStreetToken = () => localStorage.getItem('street_token') || ''
