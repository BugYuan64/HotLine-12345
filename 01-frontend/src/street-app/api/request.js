import { createRequest } from '../../shared/request'
import { useAuthStore } from '../stores/auth'

/** 街道办请求实例（登录失效跳转 /street/login，凭证走 streetAuth 独立存储） */
const request = createRequest({
  getAuthStore: useAuthStore,
  loginPath: '/street/login'
})

export default request
