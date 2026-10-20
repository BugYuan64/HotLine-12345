import { createRequest } from '../../shared/request'
import { useAuthStore } from '../stores/auth'

/** 管理端请求实例（登录失效跳转 /admin/login） */
const request = createRequest({
  getAuthStore: useAuthStore,
  loginPath: '/admin/login'
})

export default request
