<template>
  <div class="grid-wrapper">
    <div class="grid-background"></div>
    <!-- 登录表单（无卡片边框，融入背景） -->
    <div class="form">
      <div class="login-header">
        <h2>部门工作人员登录</h2>
        <p>12345 政务热线 · 高效处理每一件民生诉求</p>
        <p class="entry-tip">仅限职能部门人员登录；街道办人员请从街道办专属入口登录</p>
      </div>

      <!-- 工号或手机号 + 密码（统一账号板块，无需切换）+ 图形验证码 -->
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
        <div class="flex-column">
          <label for="login-jobNo">工号 / 手机号</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="0 0 32 32" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="M16 16c2.7 0 4.8-2.1 4.8-4.8S18.7 6.4 16 6.4 11.2 8.5 11.2 11.2 13.3 16 16 16zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z"/>
            </svg>
            <el-input id="login-jobNo" v-model="form.jobNo" placeholder="请输入工号或手机号" maxlength="20" clearable class="input-wrap" autocomplete="username" />
          </div>
        </div>

        <div class="flex-column">
          <label for="login-password">登录密码</label>
          <div class="inputForm">
            <svg class="input-icon" height="20" viewBox="-64 0 512 512" width="20" xmlns="http://www.w3.org/2000/svg">
              <path d="m336 512h-288c-26.453125 0-48-21.523438-48-48v-224c0-26.476562 21.546875-48 48-48h288c26.453125 0 48 21.523438 48 48v224c0 26.476562-21.546875 48-48 48zm-288-288c-8.8125 0-16 7.167969-16 16v224c0 8.832031 7.1875 16 16 16h288c8.8125 0 16-7.167969 16-16v-224c0-8.832031-7.1875-16-16-16zm0 0"/><path d="m304 224c-8.832031 0-16-7.167969-16-16v-80c0-52.929688-43.070312-96-96-96s-96 43.070312-96 96v80c0 8.832031-7.167969 16-16 16s-16-7.167969-16-16v-80c0-70.59375 57.40625-128 128-128s128 57.40625 128 128v80c0 8.832031-7.167969 16-16 16zm0 0"/>
            </svg>
            <el-input id="login-password" v-model="form.password" type="password" show-password placeholder="请输入登录密码" class="input-wrap" @keyup.enter="handleLogin" />
          </div>
        </div>

        <div class="flex-row">
          <label class="opt-label">
            <el-checkbox v-model="rememberMe" size="small" label="记住登录状态" />
          </label>
          <span class="span" @click="goForgot">忘记密码？</span>
        </div>

        <button type="button" class="button-submit" :disabled="loading" @click="handleLogin">
          {{ loading ? '登录中...' : '进入工作台' }}
        </button>
      </el-form>

      <p class="p">没有账号？<span class="link" @click="goRegister">立即注册</span></p>
      <p class="p">如无法登录，请联系管理员进行账号授权</p>
    </div>

    <!-- 拼图滑块验证码弹窗（与市民端一致） -->
    <SlideCaptcha v-model="showSlide" @success="doLogin" />
  </div>
</template>

<script setup>
// 部门/街道办人员登录（API 9.1）：工号或手机号 + 密码 + 滑动拼图验证（与市民端一致）；待审核/停用账号后端拦截（40103/40104）
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { staffLogin } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import SlideCaptcha from '../../shared/SlideCaptcha.vue'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const form = ref({ jobNo: '', password: '' })
const loading = ref(false)
const rememberMe = ref(false)
const showSlide = ref(false) // 滑动验证弹窗显示

const rules = {
  jobNo: [{ required: true, message: '工号或手机号不能为空', trigger: 'blur' }],
  password: [{ required: true, message: '登录密码不能为空', trigger: 'blur' }]
}

// 忘记密码：跳转部门端重置密码页
const goForgot = () => router.push('/staff/forgot')
// 部门注册：跳转部门端注册页（工号由后端自动分配）
const goRegister = () => router.push('/staff/register')

// 点击登录：先校验表单，通过后弹出滑动拼图验证
const handleLogin = () => {
  formRef.value.validate().then(() => {
    showSlide.value = true
  }).catch(() => { /* 校验失败提示由表单展示 */ })
}

// 滑动验证通过后执行真实登录
const doLogin = async () => {
  loading.value = true
  try {
    const data = await staffLogin(form.value.jobNo, form.value.password)
    auth.setLogin(data, 'staff')
    ElMessage.success('登录成功')
    // 差异化跳转：部门用户跳转管辖范围工单首页（需求 3(12)）
    router.push(router.currentRoute.value.query.redirect || '/staff')
  } catch {
    /* 错误提示由请求封装统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ========== Uiverse 网格背景 + 无边框横板 ========== */
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
  gap: 8px;
  padding: 16px 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
.login-header {
  margin-bottom: 24px;
}
.login-header h2 {
  font-size: 26px;
  font-weight: 700;
  color: #151717;
  margin: 0 0 6px;
}
.login-header p {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}
.login-header p.entry-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #b45309;
}
.flex-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 18px;
}
.flex-column > label {
  color: #475569;
  font-weight: 600;
  font-size: 14px;
}
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
.flex-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  justify-content: space-between;
  margin: 4px 0;
}
.opt-label {
  font-size: 14px;
  color: #475569;
  font-weight: 400;
}
.span {
  font-size: 14px;
  color: #2d79f3;
  font-weight: 500;
  cursor: pointer;
}
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
.p {
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  margin: 8px 0;
  line-height: 1.6;
}
.link {
  color: #2d79f3;
  font-weight: 500;
  cursor: pointer;
}
</style>