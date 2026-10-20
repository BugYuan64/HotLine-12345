import request from './request'

/** 发送短信验证码（API 3.2，前端滑动验证已替代图形验证码） */
export const sendSmsCode = (phone, scene) =>
  request.post('/public/sms/code', { phone, scene })

/** 发送邮箱验证码（注册邮箱验证码功能，滑动拼图验证在前端完成） */
export const sendEmailCode = (email, scene) =>
  request.post('/public/email/code', { email, scene })

/** 市民验证码登录（API 3.3，前端滑动验证已替代图形验证码） */
export const citizenLogin = (phone, sms_code) =>
  request.post('/public/login', { phone, sms_code })

/** 工作人员自助注册（API 3.1，邮箱验证码） */
export const staffRegister = (data) =>
  request.post('/public/staff-register', data)

/** 市民自助注册（短信验证码 + 区划资料，落 citizen_account） */
export const citizenRegister = (data) =>
  request.post('/public/citizen-register', data)

/** 市民邮箱验证码登录（仅已注册邮箱，验证码经 /public/email/code 发送） */
export const citizenEmailLogin = (email, email_code) =>
  request.post('/public/login-email', { email, email_code })

/** 市民个人资料（个人中心展示 + 诉求提交默认值） */
export const getCitizenProfile = () =>
  request.get('/public/citizen/profile')

/** 退出登录 */
export const logout = () =>
  request.post('/public/logout')
