<template>
  <div class="grid-wrapper">
    <div class="grid-background"></div>
    <!-- 两栏布局：左侧宣传文字 + 右侧表单 -->
    <div class="login-layout">
      <!-- 左侧宣传区 -->
      <div class="hero-text">
        <h1 class="hero-title">我们替您解决！</h1>
        <h1 class="hero-title">您的诉求，</h1>
      </div>
      <!-- 右侧登录表单 -->
      <div class="form">
      <header class="login-header">
        <h2>12345 政务热线</h2>
        <p>登录后可提交诉求、查询办理进度、接收消息提醒</p>
      </header>

      <div class="login-tabs">
        <div class="login-tab" :class="{ active: loginMode === 'sms' }" @click="switchMode('sms')">短信登录</div>
        <div class="login-tab" :class="{ active: loginMode === 'email' }" @click="switchMode('email')">邮箱登录</div>
      </div>

      <!-- 短信验证码登录 -->
      <el-form v-if="loginMode === 'sms'" ref="smsFormRef" :model="smsForm" :rules="smsRules" size="large" @submit.prevent>
        <div class="flex-column">
          <label for="sms-phone">手机号</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="0 0 32 32" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="M26 2H6a2 2 0 0 0-2 2v24a2 2 0 0 0 2 2h20a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zm-4 24H10v-2h12v2zm4-4H6V6h20v16z"/>
            </svg>
            <span class="input-prefix">+86</span>
            <el-input id="sms-phone" v-model="smsForm.phone" placeholder="请输入11位手机号" maxlength="11" clearable class="input-wrap" />
          </div>
        </div>
        <div class="flex-column">
          <label for="sms-code">短信验证码</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="0 0 512 512" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="M256 64C120 64 16 168 16 304c0 136 104 240 240 240s240-104 240-240C496 168 392 64 256 64zm0 416c-97.2 0-176-78.8-176-176s78.8-176 176-176 176 78.8 176 176-78.8 176-176 176z"/>
            </svg>
            <el-input id="sms-code" v-model="smsForm.smsCode" placeholder="6位短信验证码" maxlength="6" clearable class="input-wrap" />
            <button type="button" class="code-btn" :disabled="smsCountdown > 0 || !phoneValid" @click="handleSendSms">
              {{ smsSending ? '发送中...' : (smsCountdown > 0 ? smsCountdown + 's后重发' : '获取验证码') }}
            </button>
          </div>
        </div>
        <button type="button" class="button-submit" :disabled="loading" @click="handleSmsLogin">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </el-form>

      <!-- 邮箱验证码登录（仅已注册邮箱，未注册需先注册） -->
      <el-form v-if="loginMode === 'email'" ref="emailFormRef" :model="emailForm" :rules="emailRules" size="large" @submit.prevent>
        <div class="flex-column">
          <label for="email-account">QQ邮箱</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="0 0 32 32" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="M28 6H4a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h24a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm-2.4 2L16 16.8 6.4 8h19.2zM4 22V9.6l12 8 12-8V22H4z"/>
            </svg>
            <el-input id="email-account" v-model="emailForm.email" placeholder="注册时填写的QQ邮箱" maxlength="100" clearable class="input-wrap" />
          </div>
        </div>
        <div class="flex-column">
          <label for="email-code">邮箱验证码</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="0 0 512 512" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="M256 64C120 64 16 168 16 304c0 136 104 240 240 240s240-104 240-240C496 168 392 64 256 64zm0 416c-97.2 0-176-78.8-176-176s78.8-176 176-176 176 78.8 176 176-78.8 176-176 176z"/>
            </svg>
            <el-input id="email-code" v-model="emailForm.emailCode" placeholder="6位邮箱验证码" maxlength="6" clearable class="input-wrap" />
            <button type="button" class="code-btn" :disabled="emailCountdown > 0 || !emailValid || emailSending" @click="handleSendEmailCode">
              {{ emailSending ? '发送中...' : (emailCountdown > 0 ? emailCountdown + 's后重发' : '获取验证码') }}
            </button>
          </div>
        </div>
        <button type="button" class="button-submit" :disabled="emailLoading" @click="handleEmailLogin">
          {{ emailLoading ? '登录中...' : '登 录' }}
        </button>
      </el-form>

      <div class="login-footer">
        <span>手机号或邮箱登录，未注册的手机号/邮箱请先注册</span><br>
        <span>也可</span>
        <el-link type="primary" underline="never" @click="$router.push('/complaint')">免登录快速提交诉求</el-link>
        <span> 或 </span>
        <el-link type="primary" underline="never" @click="$router.push('/register')">注册新账号</el-link>
      </div>
      </div><!-- /.form -->
    </div><!-- /.login-layout -->

    <!-- 拼图滑块验证码弹窗（公共组件，与 admin/staff 端一致） -->
    <SlideCaptcha v-model="showSlideCaptcha" @success="sendSmsAfterCaptcha" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendSmsCode, sendEmailCode, citizenLogin, citizenEmailLogin } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import SlideCaptcha from '../../shared/SlideCaptcha.vue'

const router = useRouter()
const auth = useAuthStore()

const PHONE_REGEX = /^1[3-9]\d{9}$/
const loading = ref(false)

// ---- 短信登录 ----
const smsFormRef = ref()
const smsForm = ref({ phone: '', smsCode: '' })
const smsSending = ref(false)
const smsCountdown = ref(0)
const phoneValid = computed(() => PHONE_REGEX.test(smsForm.value.phone))

const smsRules = {
  phone: [{ required: true, message: '手机号不能为空', trigger: 'blur' }, { pattern: PHONE_REGEX, message: '手机号格式不正确', trigger: 'blur' }],
  smsCode: [{ required: true, message: '验证码不能为空', trigger: 'blur' }, { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }]
}

/* ========== 拼图滑块验证码（公共组件 SlideCaptcha） ========== */
const showSlideCaptcha = ref(false)

/* ========== 发送短信 & 登录 ========== */
let pendingSmsCallback = null

const handleSendSms = () => {
  if (!phoneValid.value) { ElMessage.warning('请输入正确的11位手机号'); return }
  // 弹出拼图验证码（组件打开时自动初始化，验证通过后触发 success）
  showSlideCaptcha.value = true
  // 保存回调，验证通过后调用
  pendingSmsCallback = () => {
    smsSending.value = true
    sendSmsCode(smsForm.value.phone, 'LOGIN')
      .then(data => {
        smsCountdown.value = data.retry_after || 60
        ElMessage.success('验证码已发送，有效期' + (data.expire_seconds || 300) + '秒')
        const timer = setInterval(() => { smsCountdown.value--; if (smsCountdown.value <= 0) clearInterval(timer) }, 1000)
      })
      .catch(() => { ElMessage.error('验证码发送失败，请稍后重试') })
      .finally(() => { smsSending.value = false })
  }
}

const sendSmsAfterCaptcha = () => {
  if (pendingSmsCallback) {
    pendingSmsCallback()
    pendingSmsCallback = null
  }
}
const handleSmsLogin = async () => {
  await smsFormRef.value.validate(); loading.value = true
  try {
    const data = await citizenLogin(smsForm.value.phone, smsForm.value.smsCode)
    auth.setLogin(data)
    ElMessage.success('登录成功')
    router.push(router.currentRoute.value.query.redirect || '/')
  } catch { smsForm.value.smsCode = '' } finally { loading.value = false }
}

// 登录方式：sms=手机号短信验证码（默认）；email=QQ邮箱验证码（仅已注册邮箱，未注册需先注册）
const loginMode = ref('sms')
const switchMode = (mode) => { loginMode.value = mode }

const EMAIL_REGEX = /^[\w.%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const emailFormRef = ref()
const emailForm = ref({ email: '', emailCode: '' })
const emailValid = computed(() => EMAIL_REGEX.test(emailForm.value.email))
const emailRules = {
  email: [{ required: true, message: '邮箱不能为空', trigger: 'blur' }, { pattern: EMAIL_REGEX, message: '邮箱格式不正确', trigger: 'blur' }],
  emailCode: [{ required: true, message: '验证码不能为空', trigger: 'blur' }, { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }]
}
const emailLoading = ref(false)
const emailSending = ref(false)
const emailCountdown = ref(0)

/** 发送邮箱验证码（后端校验邮箱已注册：未注册提示先注册） */
const handleSendEmailCode = () => {
  if (!emailValid.value) { ElMessage.warning('请输入正确的邮箱地址'); return }
  emailSending.value = true
  sendEmailCode(emailForm.value.email, 'LOGIN')
    .then(data => {
      emailCountdown.value = data.retry_after || 60
      ElMessage.success('验证码已发送至邮箱，有效期' + (data.expire_seconds || 300) + '秒')
      const timer = setInterval(() => { emailCountdown.value--; if (emailCountdown.value <= 0) clearInterval(timer) }, 1000)
    })
    .catch(() => { /* 后端错误信息（如"该邮箱未注册，请先注册后再登录"）由请求层统一弹出 */ })
    .finally(() => { emailSending.value = false })
}

const handleEmailLogin = async () => {
  await emailFormRef.value.validate(); emailLoading.value = true
  try {
    const data = await citizenEmailLogin(emailForm.value.email, emailForm.value.emailCode)
    auth.setLogin(data)
    ElMessage.success('登录成功')
    router.push(router.currentRoute.value.query.redirect || '/')
  } catch { emailForm.value.emailCode = '' } finally { emailLoading.value = false }
}
// 旧占位说明：账号密码/QQ 邮箱登录占位区已由上方邮箱登录实现替代
</script>

<style scoped>
/* ========== Uiverse 网格背景 + 拉宽矮版 + 有边框输入框 ========== */
.grid-wrapper {
  min-height: 100vh;
  width: 100%;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background-color: #f8fafc;
  overflow: hidden;
}
.grid-background {
  position: absolute;
  top: 0; right: 0; bottom: 0; left: 0;
  z-index: 0;
  background-image: linear-gradient(to right, #e2e8f0 1px, transparent 1px),
    linear-gradient(to bottom, #e2e8f0 1px, transparent 1px);
  background-size: 20px 30px;
  -webkit-mask-image: radial-gradient(ellipse 70% 60% at 50% 0%, #000 60%, transparent 100%);
  mask-image: radial-gradient(ellipse 70% 60% at 50% 0%, #000 60%, transparent 100%);
}
/* 两栏布局 */
.login-layout {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  gap: 60px;
}
/* 左侧宣传文字 —— 两列竖排，往左上偏移 */
.hero-text {
  display: flex;
  flex-direction: row;
  gap: 12px;
  margin-top: -20px;
  margin-left: -160px;
}
.hero-title {
  font-family: 'SimSun', '宋体', serif;
  font-size: 48px;
  font-weight: 700;
  color: #151717;
  line-height: 1.3;
  margin: 0;
  letter-spacing: 2px;
  /* 竖排文字 */
  writing-mode: vertical-rl;
  text-orientation: upright;
  white-space: nowrap;
}
/* 右侧表单区 */
.form {
  position: relative;
  z-index: 1;
  width: 520px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
.login-header {
  margin-bottom: 16px;
}
.login-header h2 {
  font-size: 24px;
  font-weight: 700;
  color: #151717;
  margin: 0 0 4px;
}
.login-header p {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}
/* Tab —— 无边缘线，矮版 */
.login-tabs {
  display: flex;
  gap: 32px;
  margin: 12px 0 16px;
}
.login-tab {
  font-size: 14px;
  font-weight: 500;
  color: #94a3b8;
  cursor: pointer;
  padding: 6px 0;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all 0.2s ease;
}
.login-tab.active {
  color: #2d79f3;
  font-weight: 600;
  border-bottom-color: #2d79f3;
}
.flex-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}
.flex-column > label {
  color: #475569;
  font-weight: 600;
  font-size: 13px;
}
/* 输入框 —— 有边框 + 圆角 */
.inputForm {
  border: 1.5px solid #ecedec;
  border-radius: 8px;
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 10px;
  gap: 8px;
  transition: border-color 0.2s ease-in-out;
  background: #fff;
}
.inputForm:focus-within {
  border: 1.5px solid #2d79f3;
}
.input-icon {
  color: #94a3b8;
  flex-shrink: 0;
}
.input-prefix {
  font-size: 13px;
  color: #475569;
  font-weight: 500;
  flex-shrink: 0;
}
:deep(.input-wrap) {
  flex: 1;
  min-width: 0;
}
:deep(.input-wrap .el-input__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding: 0;
}
:deep(.input-wrap .el-input__inner) {
  height: 38px;
  font-size: 14px;
  color: #151717;
}
:deep(.input-wrap .el-input__inner::placeholder) {
  color: #94a3b8;
}
::placeholder {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
.code-btn {
  white-space: nowrap;
  border: none;
  background: #2d79f3;
  color: #fff;
  border-radius: 6px;
  height: 30px;
  padding: 0 12px;
  font-size: 12px;
  cursor: pointer;
  flex-shrink: 0;
  transition: 0.2s ease-in-out;
}
.code-btn:hover { background: #1f5fd8; }
.code-btn:disabled { opacity: 0.5; cursor: not-allowed; }
/* 提交按钮 */
.button-submit {
  margin: 16px 0 6px;
  background-color: #151717;
  border: none;
  color: white;
  font-size: 14px;
  font-weight: 500;
  border-radius: 8px;
  height: 42px;
  width: 100%;
  cursor: pointer;
  transition: background-color 0.2s ease-in-out;
}
.button-submit:hover { background-color: #252727; }
.button-submit:disabled { opacity: 0.55; cursor: not-allowed; }
/* 空状态 */
.mode-placeholder {
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.placeholder-icon { font-size: 32px; }
.placeholder-title { font-size: 15px; font-weight: 600; color: #151717; }
.placeholder-desc { font-size: 13px; color: #64748b; text-align: center; line-height: 1.6; }
.mode-placeholder .button-submit { margin-top: 8px; width: auto; padding: 0 18px; }
/* 分隔线 */
.login-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
  color: #94a3b8;
  font-size: 12px;
}
.login-divider::before,
.login-divider::after {
  content: "";
  flex: 1;
  height: 1px;
  background: #e2e8f0;
}
/* 第三方登录 */
.quick-login {
  display: flex;
  justify-content: center;
  gap: 20px;
}
.quick-login-btn {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #fff;
  cursor: pointer;
  transition: all 0.25s ease;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
}
.quick-login-btn:hover {
  border-color: #2d79f3;
  color: #2d79f3;
  transform: translateY(-2px);
}
.login-footer {
  margin-top: 8px;
  text-align: center;
  font-size: 12px;
  color: #94a3b8;
}

/* 响应式 */
@media (max-width: 900px) {
  .login-layout { flex-direction: column; gap: 24px; align-items: center; }
  .hero-text { flex-direction: column; gap: 0; margin: 0; }
  .hero-title {
    writing-mode: horizontal-tb;
    text-orientation: mixed;
    font-size: 32px;
  }
  .form { width: 100%; }
}
</style>
