import request from './request'

/** 获取图形验证码（登录前置，防人机） */
export const getCaptcha = () =>
  request.get('/public/captcha')

/** 部门人员登录（API 9.1）：滑动拼图验证在前端完成，captcha 参数保留兼容后端（可选）；
 *  街道办人员登录走独立应用 street-app（/street/auth/login），两端互不相通 */
export const staffLogin = (job_no, password, captcha_id = null, captcha_code = null) =>
  request.post('/staff/auth/login', { job_no, password, captcha_id, captcha_code })

/** 发送短信验证码（注册/重置密码等场景） */
export const sendSmsCode = (phone, scene) =>
  request.post('/public/sms/code', { phone, scene })

/** 部门工作人员自助注册（工号由用户手动填写） */
export const staffDeptRegister = (data) =>
  request.post('/staff/register', data)

/** 街道办工作人员自助注册（工号由用户手动填写，地区按区县→乡镇级级联） */
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

/** 修改登录密码（API 9.10） */
export const changePassword = (data) =>
  request.put('/staff/profile/password', data)

/** 个人登录日志查询（API 9.11） */
export const loginLogs = (params) =>
  request.get('/staff/profile/logs', { params })

/** 退出登录 */
export const logout = () =>
  request.post('/staff/auth/logout')
