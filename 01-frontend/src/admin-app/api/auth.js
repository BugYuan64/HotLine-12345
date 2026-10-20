import request from './request'

/** 获取图形验证码（登录前置，防人机） */
export const getCaptcha = () =>
  request.get('/public/captcha')

/** 管理员登录（API 5.1）：滑动拼图验证在前端完成，captcha 参数保留兼容后端（可选） */
export const adminLogin = (job_no, password, captcha_id = null, captcha_code = null) =>
  request.post('/admin/auth/login', { job_no, password, captcha_id, captcha_code })

/** 退出登录 */
export const logout = () =>
  request.post('/admin/auth/logout')
