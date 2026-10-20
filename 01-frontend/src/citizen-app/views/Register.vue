<template>
  <div class="register-page">
    <header class="page-header">
      <div class="header-back" @click="$router.push('/login')"><span>←</span><span>返回登录</span></div>
      <h2>市民注册</h2>
    </header>
    <main class="register-main">
      <el-card shadow="never" class="register-card">
        <div class="card-desc"><p>注册后可用手机号短信验证码登录，也可使用QQ邮箱验证码登录；资料将作为提交诉求的默认信息</p></div>

        <!-- 基本信息 -->
        <div class="section-title">基本信息</div>
        <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent>
          <el-form-item label="姓名" prop="name">
            <el-input v-model="form.name" placeholder="请输入姓名" maxlength="50" clearable />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入11位手机号" maxlength="11" clearable><template #prepend>+86</template></el-input>
          </el-form-item>
          <el-form-item label="短信验证码" prop="smsCode">
            <div class="code-row">
              <el-input v-model="form.smsCode" placeholder="6位短信验证码" maxlength="6" clearable />
              <el-button :disabled="countdown > 0 || !PHONE_REGEX.test(form.phone)" :loading="smsSending" class="code-btn" @click="handleSendSmsCode">{{ countdown > 0 ? countdown + 's后重发' : '获取验证码' }}</el-button>
            </div>
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="选填，邮箱验证码登录使用" maxlength="100" clearable />
          </el-form-item>
        </el-form>

        <!-- 居住信息 -->
        <div class="section-title">居住信息</div>
        <el-form ref="addrFormRef" :model="form" :rules="addrRules" label-width="100px" @submit.prevent>
          <el-form-item label="辖区" prop="districtCode">
            <el-select v-model="form.districtCode" placeholder="请选择辖区" style="width: 100%" @change="onDistrictChange">
              <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
            </el-select>
          </el-form-item>
          <el-form-item label="街道" prop="streetCode">
            <el-select v-model="form.streetCode" placeholder="请先选择辖区" style="width: 100%" :disabled="!form.districtCode" @change="onStreetChange">
              <el-option v-for="s in streets" :key="s.region_code" :label="s.region_name" :value="s.region_code" />
            </el-select>
          </el-form-item>
          <el-form-item label="详细住址" prop="addressDetail">
            <el-input v-model="form.addressDetail" placeholder="如：文化路 123 号" maxlength="200" clearable />
          </el-form-item>
        </el-form>

        <!-- 登录密码 -->
        <div class="section-title">登录密码</div>
        <el-form :model="form" label-width="100px" @submit.prevent>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" show-password placeholder="8-20位，含数字/字母/特殊字符" maxlength="20" />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" maxlength="20" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="submit-btn" :loading="saving" @click="handleRegister">注册</el-button>
          </el-form-item>
        </el-form>
        <div class="register-tip">已有账号？<el-link type="primary" underline="never" @click="$router.push('/login')">立即登录</el-link></div>
      </el-card>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { citizenRegister, sendSmsCode, getCitizenProfile } from '../api/auth'
import { getCounties, getRegionChildren } from '../api/appeal'

const router = useRouter()
const formRef = ref()
const addrFormRef = ref()
const saving = ref(false)
const smsSending = ref(false)
const countdown = ref(0)
const PHONE_REGEX = /^1[3-9]\d{9}$/

const form = reactive({
  name: '', phone: '', smsCode: '', email: '',
  districtCode: '', district: '', streetCode: '', street: '', addressDetail: '',
  password: '', confirmPassword: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.password) callback(new Error('两次密码输入不一致'))
  else callback()
}

const rules = {
  name: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
  phone: [{ required: true, message: '手机号不能为空', trigger: 'blur' }, { pattern: PHONE_REGEX, message: '手机号格式不正确', trigger: 'blur' }],
  smsCode: [{ required: true, message: '短信验证码不能为空', trigger: 'blur' }, { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }],
  password: [{ required: true, message: '密码不能为空', trigger: 'blur' }, { min: 8, max: 20, message: '密码长度8-20位', trigger: 'blur' }, { pattern: /^(?=.*\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$/, message: '密码需包含数字、字母和特殊字符', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
}
// 居住信息必填规则：辖区/街道/详细住址全部必填
const addrRules = {
  districtCode: [{ required: true, message: '请选择辖区', trigger: 'change' }],
  streetCode: [{ required: true, message: '请选择街道', trigger: 'change' }],
  addressDetail: [{ required: true, message: '详细住址不能为空', trigger: 'blur' }]
}

// ---- 区划联动（区县 → 街道/镇/乡，与街道办注册同源接口） ----
const counties = ref([])
const streets = ref([])
onMounted(async () => {
  try { counties.value = await getCounties() } catch { /* ignore */ }
})
const onDistrictChange = async (code) => {
  form.streetCode = ''
  form.street = ''
  streets.value = []
  if (!code) return
  try { streets.value = await getRegionChildren(code) } catch { /* ignore */ }
}
const onStreetChange = (code) => {
  const s = streets.value.find(x => x.region_code === code)
  form.street = s ? s.region_name : ''
}

// ---- 短信验证码（阿里短信） ----
const handleSendSmsCode = async () => {
  if (!PHONE_REGEX.test(form.phone)) { ElMessage.warning('请输入正确的手机号'); return }
  smsSending.value = true
  try {
    const data = await sendSmsCode(form.phone, 'REGISTER')
    countdown.value = data.retry_after || 60
    ElMessage.success('验证码已发送至您的手机')
    const timer = setInterval(() => { countdown.value--; if (countdown.value <= 0) clearInterval(timer) }, 1000)
  } catch { /* interceptor 提示 */ }
  finally { smsSending.value = false }
}

// ---- 提交注册 ----
const handleRegister = async () => {
  // 基本信息 + 居住信息两表单都通过校验才提交
  await formRef.value.validate()
  await addrFormRef.value.validate()
  saving.value = true
  try {
    const district = counties.value.find(c => c.region_code === form.districtCode)
    form.district = district ? district.region_name : ''
    await citizenRegister({
      name: form.name,
      phone: form.phone,
      sms_code: form.smsCode,
      password: form.password,
      confirm_password: form.confirmPassword,
      email: form.email || undefined,
      district_code: form.districtCode,
      district: form.district,
      street_code: form.streetCode,
      street: form.street,
      address_detail: form.addressDetail
    })
    ElMessage.success('注册成功！请登录')
    router.push('/login')
  } catch { /* interceptor 提示 */ }
  finally { saving.value = false }
}

// 供后续“资料预填”复用的 profile 拉取（登录状态下进入本页时不需要，保留工具化引用）
void getCitizenProfile
</script>

<style scoped>
.register-page { min-height: 100vh; background: #f5f6fa; }
.page-header { display: flex; align-items: center; gap: 12px; padding: 16px 20px; background: #fff; border-bottom: 1px solid #ececec; }
.header-back { color: #64748b; cursor: pointer; display: flex; gap: 6px; align-items: center; font-size: 14px; }
.page-header h2 { font-size: 17px; color: #18181b; }
.register-main { max-width: 640px; margin: 20px auto; padding: 0 16px; }
.register-card { border-radius: 10px; }
.card-desc p { color: #64748b; font-size: 13px; margin: 0 0 4px; }
.section-title { font-size: 14px; font-weight: 600; color: #1f2937; margin: 14px 0 12px; padding-left: 8px; border-left: 3px solid #409eff; }
.code-row { display: flex; gap: 10px; width: 100%; }
.code-btn { flex-shrink: 0; }
.submit-btn { width: 100%; }
.register-tip { text-align: center; color: #64748b; font-size: 13px; }
</style>
