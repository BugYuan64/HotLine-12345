<template>
  <div class="grid-wrapper">
    <div class="grid-background"></div>
    <div class="form">
      <div class="header">
        <h2>忘记密码</h2>
        <p>通过已注册的手机号 + 短信验证码重置登录密码</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top" @submit.prevent>
        <div class="flex-column">
          <label for="f-phone">已注册手机号</label>
          <div class="inputForm">
            <el-input id="f-phone" v-model="form.phone" placeholder="请输入注册时的手机号" maxlength="11" clearable class="input-wrap" />
          </div>
        </div>

        <div class="flex-column">
          <label for="f-code">短信验证码</label>
          <div class="inputForm code-row">
            <el-input id="f-code" v-model="form.smsCode" placeholder="请输入6位验证码" maxlength="6" clearable class="input-wrap" />
            <button type="button" class="send-code" :disabled="sending || countDown > 0" @click.prevent="handleSendCode">
              {{ countDown > 0 ? `${countDown}s后重发` : '获取验证码' }}
            </button>
          </div>
        </div>

        <div class="flex-column">
          <label for="f-pwd">新密码</label>
          <div class="inputForm">
            <el-input id="f-pwd" v-model="form.password" type="password" show-password placeholder="8位以上，含数字、字母、特殊字符" clearable class="input-wrap" />
          </div>
        </div>

        <div class="flex-column">
          <label for="f-pwd2">确认新密码</label>
          <div class="inputForm">
            <el-input id="f-pwd2" v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" clearable class="input-wrap" @keyup.enter="handleReset" />
          </div>
        </div>

        <button type="button" class="button-submit" :disabled="loading" @click="handleReset">
          {{ loading ? '提交中...' : '重置密码' }}
        </button>
      </el-form>

      <p class="p">记起密码了？<span class="link" @click="goLogin">返回登录</span></p>
    </div>
  </div>
</template>

<script setup>
// 部门账号忘记密码：手机号 + 短信验证码 校验后重置登录密码（不改变工号/手机号）
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { resetPassword, sendSmsCode } from '../api/auth'

const router = useRouter()
const formRef = ref()
const form = ref({ phone: '', smsCode: '', password: '', confirmPassword: '' })
const loading = ref(false)
const sending = ref(false)
const countDown = ref(0)

const rules = {
  phone: [
    { required: true, message: '手机号不能为空', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  smsCode: [
    { required: true, message: '验证码不能为空', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '新密码不能为空', trigger: 'blur' },
    { pattern: /^(?=.*\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$/, message: '需8位以上，含数字、字母、特殊字符', trigger: 'blur' }
  ],
  confirmPassword: [{ required: true, message: '确认新密码不能为空', trigger: 'blur' }]
}

// 发送短信验证码（scene=RESET_PASSWORD），60 秒倒计时
const handleSendCode = () => {
  formRef.value.validateField('phone').then(async () => {
    sending.value = true
    try {
      await sendSmsCode(form.value.phone, 'RESET_PASSWORD')
      ElMessage.success('验证码已发送')
      countDown.value = 60
      const timer = setInterval(() => {
        countDown.value -= 1
        if (countDown.value <= 0) clearInterval(timer)
      }, 1000)
    } catch {
      /* 错误提示由请求封装统一处理 */
    } finally {
      sending.value = false
    }
  }).catch(() => { /* 校验失败提示由表单展示 */ })
}

const handleReset = () => {
  formRef.value.validate().then(async () => {
    if (form.value.password !== form.value.confirmPassword) {
      ElMessage.error('两次输入的新密码不一致')
      return
    }
    loading.value = true
    try {
      await resetPassword(form.value)
      ElMessage.success('密码重置成功，请使用新密码登录')
      router.push('/staff/login')
    } catch {
      /* 错误提示由请求封装统一处理 */
    } finally {
      loading.value = false
    }
  }).catch(() => { /* 校验失败提示由表单展示 */ })
}

const goLogin = () => router.push('/staff/login')
</script>

<style scoped>
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
.form {
  position: relative;
  z-index: 1;
  width: 720px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
.header { margin-bottom: 20px; }
.header h2 { font-size: 26px; font-weight: 700; color: #151717; margin: 0 0 6px; }
.header p { font-size: 14px; color: #64748b; margin: 0; }
.flex-column { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.flex-column > label { color: #475569; font-weight: 600; font-size: 14px; }
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
.inputForm:focus-within { border: 1.5px solid #2d79f3; }
:deep(.input-wrap) { flex: 1; min-width: 0; }
:deep(.input-wrap .el-input__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding: 0;
}
:deep(.input-wrap .el-input__inner) { height: 38px; font-size: 14px; color: #151717; }
:deep(.input-wrap .el-input__inner::placeholder) { color: #94a3b8; }
::placeholder { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif; }
.code-row { justify-content: space-between; padding: 0 6px 0 10px; }
.send-code {
  flex-shrink: 0;
  background: #151717;
  color: #fff;
  border: none;
  border-radius: 6px;
  height: 30px;
  padding: 0 12px;
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.2s ease-in-out;
}
.send-code:hover { background-color: #252727; }
.send-code:disabled { opacity: 0.55; cursor: not-allowed; }
.button-submit {
  margin: 8px 0 6px;
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
.p { text-align: center; color: #94a3b8; font-size: 13px; margin: 8px 0; line-height: 1.6; }
.link { color: #2d79f3; font-weight: 500; cursor: pointer; }
</style>