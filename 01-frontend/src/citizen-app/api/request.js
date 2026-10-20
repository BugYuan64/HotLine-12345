import { createRequest } from '../../shared/request'
import { useAuthStore, readCitizenToken } from '../stores/auth'

/** 市民端请求实例（登录失效跳转 /login） */
const request = createRequest({
  getAuthStore: useAuthStore,
  /** 注入 header 时同步读 localStorage，刷新后 token 不丢 */
  getToken: () => readCitizenToken(),
  loginPath: '/login'
})

export default request
