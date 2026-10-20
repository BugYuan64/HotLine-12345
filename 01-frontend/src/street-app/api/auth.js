import request from './request'

/** 街道办人员登录（独立入口 /street/auth/login）：仅放行 user_type=STREET */
export const login = (job_no, password, captcha_id = null, captcha_code = null) =>
  request.post('/street/auth/login', { job_no, password, captcha_id, captcha_code })

/** 街道办退出登录 */
export const logout = () =>
  request.post('/street/auth/logout')

/** 发送短信验证码（注册/重置密码场景） */
export const sendSmsCode = (phone, scene) =>
  request.post('/public/sms/code', { phone, scene })

/** 街道办工作人员自助注册（工号由用户手动填写，地区按区县→乡镇级两级级联） */
export const streetRegister = (data) =>
  request.post('/street/register', data)

/** 查询全部区县（街道办注册区县下拉） */
export const getCounties = () =>
  request.get('/public/regions/counties')

/** 查询某区县下的街道/镇/乡（街道办注册乡镇级下拉） */
export const getRegionChildren = (parent) =>
  request.get('/public/regions/children', { params: { parent } })

/** 忘记密码：手机号 + 短信验证码 重置登录密码 */
export const resetPassword = (data) =>
  request.post('/public/reset-password', data)
