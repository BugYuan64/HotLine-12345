import { defineStore } from 'pinia'

/** 读取本地 token（路由守卫与请求拦截器统一从这里取，避免刷新后丢失） */
export function readCitizenToken() {
  try {
    const raw = localStorage.getItem('citizen_token')
    if (raw && raw.trim()) return raw.trim()
  } catch (_) { /* ignore */ }
  return ''
}

/**
 * 登录状态（需求 3(14)：前端实时展示当前登录状态，退出后清空本地身份凭证）
 * - token：始终优先从 localStorage 读取，刷新后不会丢失
 * - maskedPhone / realnameVerified：登录时写入 localStorage，便于跨刷新保留
 */
export const useAuthStore = defineStore('citizenAuth', {
  state: () => ({
    token: readCitizenToken(),
    maskedPhone: localStorage.getItem('citizen_masked_phone') || '',
    /** 实名状态：true 已实名 / false 未实名（前端模拟） */
    realnameVerified: localStorage.getItem('citizen_realname_verified') === '1'
  }),
  actions: {
    /**
     * 设置登录态
     * @param {Object} payload
     * @param {string} payload.access_token         访问令牌
     * @param {string} [payload.masked_phone]       脱敏手机号
     * @param {boolean} [payload.realname_verified] 是否已实名（默认 false）
     */
    setLogin({ access_token, masked_phone, realname_verified }) {
      this.token = access_token
      this.maskedPhone = masked_phone || ''
      this.realnameVerified = !!realname_verified
      localStorage.setItem('citizen_token', access_token)
      localStorage.setItem('citizen_masked_phone', this.maskedPhone)
      localStorage.setItem('citizen_realname_verified', this.realnameVerified ? '1' : '0')
    },
    /** 设置实名状态（前端模拟：身份核验通过/未通过） */
    setRealnameVerified(verified) {
      this.realnameVerified = !!verified
      localStorage.setItem('citizen_realname_verified', this.realnameVerified ? '1' : '0')
    },
    logout() {
      this.token = ''
      this.maskedPhone = ''
      this.realnameVerified = false
      localStorage.removeItem('citizen_token')
      localStorage.removeItem('citizen_masked_phone')
      localStorage.removeItem('citizen_realname_verified')
    }
  }
})
