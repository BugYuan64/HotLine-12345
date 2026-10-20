import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 保存流式下载响应为文件：blob 响应（拦截器透传的完整 response）→ 浏览器下载，用后即释放 objectURL
 */
export function saveBlobResponse(response, fileName) {
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/**
 * 共享请求工厂（三端共用，API 接口文档 2.3 统一响应：{code, message, data, timestamp}）
 * - code === 200 时直接返回 body.data
 * - 40101/40102（Token 无效/过期）→ 清空对应端本地凭证并跳转该端登录页
 * - 请求配置传 { skipErrorMessage: true } 可跳过失败 Toast（用于静默失败场景，如 AI 建议降级）
 *
 * @param {Object} options
 * @param {Function} options.getAuthStore 返回当前端的 Pinia auth store（按调用时机惰性求值）
 * @param {Function} [options.getToken]   读取 token 的方法；缺省时从 auth.token 取；
 *                                         若提供则优先使用（解决刷新后 Pinia 状态丢失问题）
 * @param {string}   options.loginPath   登录失效跳转的登录页路径
 */
export function createRequest({ getAuthStore, getToken, loginPath }) {
  const request = axios.create({
    baseURL: '/api/v1',
    timeout: 30000
  })

  request.interceptors.request.use((config) => {
    const token = typeof getToken === 'function' ? getToken() : getAuthStore()?.token
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  /** 统一错误口径：40101/40102（Token 无效/过期）清理凭证跳登录页，其余按需 Toast */
  const handleApiError = (body, config) => {
    const code = body?.code
    const silent = config?.skipErrorMessage
    if (code === 40101 || code === 40102) {
      const auth = getAuthStore()
      auth?.logout()
      ElMessage.error(body?.message || '登录已失效，请重新登录')
      window.location.href = loginPath
    } else if (!silent) {
      ElMessage.error(body?.message || '网络异常，请稍后再试')
    }
  }

  request.interceptors.response.use(
    (response) => {
      // 流式下载（blob）：直接透传完整响应（含 data 文件流与响应头），由调用方保存文件
      if (response.config.responseType === 'blob') {
        return response
      }
      const body = response.data
      if (body && body.code === 200) {
        return body.data
      }
      if (!response.config.skipErrorMessage) {
        ElMessage.error(body?.message || '请求失败')
      }
      return Promise.reject(new Error(body?.message || '请求失败'))
    },
    (error) => {
      const data = error.response?.data
      // blob 请求失败的错误响应体也是 Blob（JSON 被包成 Blob）：先读文本再按统一口径提示
      if (data instanceof Blob) {
        return data.text().then((text) => {
          let body = null
          try { body = JSON.parse(text) } catch { /* 非 JSON 响应体（网关错误页等），走通用提示 */ }
          handleApiError(body, error.config)
          return Promise.reject(error)
        })
      }
      handleApiError(data, error.config)
      return Promise.reject(error)
    }
  )

  return request
}
