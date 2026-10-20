import { createRequest } from '../../shared/request'
import { useAuthStore, readStaffToken } from '../stores/auth'

/** 部门端请求实例（登录失效跳转 /staff/login；注入 header 时直读 localStorage，刷新后 token 不丢） */
const request = createRequest({
  getAuthStore: useAuthStore,
  getToken: () => readStaffToken(),
  loginPath: '/staff/login'
})

export default request