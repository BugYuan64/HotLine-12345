<!--
  市民提交诉求（极简风）：套 CitizenLayout 共享布局，与办理进度等页面风格一致
-->
<template>
  <CitizenLayout title="提交诉求">
    <!-- 提示条 -->
    <el-alert type="info" :closable="false" show-icon class="tips-box">
      <template #title>
        <strong>温馨提示：</strong>请如实反映问题。涉及紧急危险情况，请直接拨打 110、119、120 等应急电话。
      </template>
    </el-alert>

    <main class="form-card">
      <!-- 诉求类型 -->
      <section class="form-section">
        <div class="section-title">
          <span class="num">1</span>
          <span>选择诉求类型</span>
        </div>
        <div class="category-grid">
          <div
            v-for="cat in categories"
            :key="cat.value"
            class="category-option"
            :class="{ active: selectedCategory === cat.value }"
            @click="selectedCategory = cat.value"
          >
            <el-icon class="category-icon"><component :is="cat.icon" /></el-icon>
            <span class="category-name">{{ cat.label }}</span>
          </div>
        </div>
        <p class="hint">诉求类型仅用于引导填写，最终类别由系统根据诉求内容智能识别</p>
      </section>

      <!-- 诉求内容 -->
      <section class="form-section">
        <div class="section-title">
          <span class="num">2</span>
          <span>填写诉求内容</span>
        </div>
        <el-form ref="formRef" :model="formData" :rules="rules" label-position="top">
          <!-- 诉求标题由管理端收到诉求后 AI 总结生成，市民端不再填写 -->

          <el-form-item label="详细描述" prop="content">
            <el-input
              v-model="formData.content"
              type="textarea"
              :rows="5"
              placeholder="请描述问题发生的时间、地点、具体情况，以及您的诉求"
              maxlength="2000"
              show-word-limit
              @input="scheduleAiSuggest"
            />
            <div v-if="aiSuggestion.missing_elements && aiSuggestion.missing_elements.length" class="ai-tip warning">
              <el-icon><WarningFilled /></el-icon>
              <span>AI 建议补充：{{ aiSuggestion.missing_elements.join('、') }}</span>
            </div>
            <div v-if="aiSuggestion.completion" class="ai-tip">
              <el-icon><InfoFilled /></el-icon>
              <span>AI 补全建议：{{ aiSuggestion.completion }}</span>
            </div>
          </el-form-item>

          <!-- 全市范围性诉求功能暂不开放：区县与详细地址均为必填 -->
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="区县" prop="districtCode">
                <el-select v-model="formData.districtCode" clearable placeholder="请选择区县"
                  style="width: 100%" @change="onDistrictChange">
                  <el-option v-for="d in districts" :key="d.region_code" :label="d.region_name" :value="d.region_code" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="街道/乡镇" prop="street">
                <el-select v-model="formData.street" clearable placeholder="请选择街道/乡镇"
                  :disabled="!formData.districtCode" style="width: 100%">
                  <el-option v-for="s in streets" :key="s.region_code" :label="s.region_name" :value="s.region_name" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :span="24">
              <el-form-item label="详细地址" prop="addressDetail">
                <el-input
                  v-model="formData.addressDetail"
                  placeholder="请输入详细地址（如：xx小区/xx路xx号）"
                  maxlength="100"
                  clearable
                />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </section>

      <!-- 附件上传 -->
      <section class="form-section">
        <div class="section-title">
          <span class="num">3</span>
          <span>上传图片（选填）</span>
        </div>
        <el-upload
          v-model:file-list="fileList"
          action="#"
          list-type="picture-card"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="handleFileChange"
          :on-remove="handleFileRemove"
          :limit="5"
          accept="image/*,.pdf,.doc,.docx,.xls,.xlsx"
        >
          <div class="upload-trigger">
            <el-icon :size="28"><Plus /></el-icon>
            <span class="upload-text">点击上传</span>
            <span class="upload-hint">最多 5 个文件,支持图片/Word/PDF /Excel,单个不超过 10MB</span>
          </div>
        </el-upload>
        <!-- 已选附件：图片缩略图 / 类型图标 + 文件名（过长省略，悬浮显示全部） -->
        <AttachmentGrid
          v-if="displayFiles.length"
          :files="displayFiles"
          removable
          @remove="removeAttach"
        />
      </section>

      <!-- 联系方式 -->
      <section class="form-section">
        <div class="section-title">
          <span class="num">4</span>
          <span>联系方式</span>
        </div>
        <div class="contact-info">
          <el-icon><Iphone /></el-icon>
          <span>已登录账号：<strong>{{ auth.maskedPhone || '游客用户' }}</strong></span>
          <span class="contact-tip">系统将以此手机号与您沟通，请确保真实有效</span>
        </div>
      </section>

      <!-- 提交按钮（内容区内，非 fixed） -->
      <div class="submit-bar">
        <el-button size="large" @click="goHome">取消</el-button>
        <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">提交诉求</el-button>
      </div>
    </main>

    <!-- 提交成功对话框：敏感诉求无编号（专人专线受理，可在"办理进度"页查看处理进度）；正常诉求显示编号供查进度 -->
    <el-dialog v-model="successDialog" :title="isSensitive ? '已受理' : '提交成功'" width="420px" :close-on-click-modal="false" align-center>
      <div class="success-content" v-if="isSensitive">
        <div class="success-icon">✓</div>
        <div class="success-title">您的诉求已识别为敏感事件</div>
        <div class="success-info">
          <p>已转专人专线受理处理，请保持电话畅通。</p>
          <p>您可在「办理进度」页面查看该事件的处理进度。</p>
        </div>
        <div class="success-actions">
          <el-button type="primary" @click="goProgress">去查看进度</el-button>
        </div>
      </div>
      <div class="success-content" v-else>
        <div class="success-icon">✓</div>
        <div class="success-title">已成功提交诉求</div>
        <div class="success-info">
          <!-- 无编号则不显示编号行（敏感诉求走上方分支，此处正常诉求必有编号） -->
          <p v-if="submittedAppealNo">诉求编号：<strong>{{ submittedAppealNo }}</strong></p>
        </div>
        <div class="success-actions">
          <el-button @click="goHome">返回首页</el-button>
          <el-button v-if="!submittedAppealNo" type="primary" @click="successDialog = false">完成</el-button>
          <el-button v-else type="primary" @click="goProgress">去查看</el-button>
        </div>
      </div>
    </el-dialog>
  </CitizenLayout>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { InfoFilled, WarningFilled, Plus, Iphone,
  OfficeBuilding, Van, Sunny, FirstAidKit, ScaleToOriginal,
  Shop, House, Suitcase, Location
} from '@element-plus/icons-vue'
import CitizenLayout from '../components/CitizenLayout.vue'
import AttachmentGrid from '../../shared/AttachmentGrid.vue'
import { useAuthStore } from '../stores/auth'
import { submitAppeal, uploadAttachment, getSuggest, getCounties, getRegionChildren } from '../api/appeal'
import { getCitizenProfile } from '../api/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref(null)
const submitting = ref(false)
const successDialog = ref(false)
const submittedAppealNo = ref('')
// 敏感诉求标记：提交返回 sensitive=true 时弹窗走"已转专线受理"分支（无编号，进度在"办理进度"页敏感事件区块查看）
const isSensitive = ref(false)

const selectedCategory = ref('OTHER')
// 类别与后端 8 大类（classify_primary）+ 其他 对齐；随诉求提交入库
const categories = [
  { value: 'URBAN', label: '城市管理', icon: OfficeBuilding },
  { value: 'TRAFFIC', label: '交通管理', icon: Van },
  { value: 'MARKET', label: '市场监管', icon: Shop },
  { value: 'ENVIRONMENT', label: '环境保护', icon: Sunny },
  { value: 'HOUSING', label: '住房保障', icon: House },
  { value: 'LABOR', label: '劳动保障', icon: Suitcase },
  { value: 'PUBLIC', label: '公共服务', icon: Location },
  { value: 'HEALTH', label: '医疗卫生', icon: FirstAidKit },
  { value: 'OTHER', label: '其他诉求', icon: ScaleToOriginal }
]

const districts = ref([])   // [{ region_code, region_name }]
const streets = ref([])     // [{ region_code, region_name, level }]
const fileList = ref([])
// 已选附件展示（图片→本地预览缩略图；文档→类型图标）+ 可移除
const displayFiles = computed(() => fileList.value.map((f, i) => ({
  file_id: f.uid || i,
  file_name: f.name,
  url: isImgRaw(f) ? URL.createObjectURL(f.raw) : undefined,
  raw: f.raw
})))
const isImgRaw = (f) => (f.raw?.type || '').startsWith('image/') || /\.(jpe?g|png|gif|bmp|webp)$/i.test(f.name || '')
const removeAttach = (f) => {
  const idx = displayFiles.value.findIndex(d => d.file_id === f.file_id)
  if (idx >= 0 && fileList.value[idx]) {
    if (fileList.value[idx].url) URL.revokeObjectURL(fileList.value[idx].url)
    fileList.value.splice(idx, 1)
  }
}

const aiSuggestion = reactive({
  suggested_title: '',
  completion: '',
  missing_elements: []
})
let suggestTimer = null

const formData = reactive({
  content: '',
  district: '',        // 区县中文名（提交用）
  districtCode: '',    // 区县编码（联动查询用）
  street: '',          // 街乡镇中文名（提交用）
  addressDetail: ''
})

const rules = {
  content: [
    { required: true, message: '请输入诉求详情', trigger: 'blur' },
    { min: 10, max: 2000, message: '详情长度在 10 到 2000 个字符', trigger: 'blur' }
  ],
  districtCode: [
    {
      validator: (rule, value, callback) => {
        if (!value) {
          callback(new Error('请选择区县'))
        } else { callback() }
      },
      trigger: 'change'
    }
  ],
  street: [
    {
      validator: (rule, value, callback) => {
        if (!value) {
          callback(new Error('请选择街道/乡镇'))
        } else { callback() }
      },
      trigger: 'change'
    }
  ],
  addressDetail: [
    {
      validator: (rule, value, callback) => {
        if (!value) {
          callback(new Error('请填写详细地址'))
        } else { callback() }
      },
      trigger: 'blur'
    }
  ]
}

const goHome = () => {
  successDialog.value = false
  router.push('/home')
}

const goProgress = () => {
  successDialog.value = false
  // 去查看：跳转办理进度列表页并携带诉求编号，列表页自动打开该诉求的进度弹窗
  router.push({ path: '/my-appeals', query: { appealNo: submittedAppealNo.value } })
}

const onDistrictChange = (code) => {
  formData.street = ''
  streets.value = []
  if (!code) {
    formData.district = ''
    return
  }
  // 同步区县中文名（提交用）
  const found = districts.value.find((d) => d.region_code === code)
  formData.district = found ? found.region_name : ''
  loadStreets(code)
}

const loadStreets = async (countyCode) => {
  try {
    const data = await getRegionChildren(countyCode)
    streets.value = Array.isArray(data) ? data : []
  } catch {
    streets.value = []
  }
}

onMounted(async () => {
  try {
    const data = await getCounties()
    districts.value = Array.isArray(data) ? data : []
  } catch {
    districts.value = []
  }
  // 登录状态下按个人资料预填：辖区/街道下拉与详细住址（可修改）
  if (auth.maskedPhone) {
    try {
      const p = await getCitizenProfile()
      if (p.registered) {
        if (p.district_code) {
          formData.districtCode = p.district_code
          formData.district = p.district || ''
          try {
            streets.value = await getRegionChildren(p.district_code)
          } catch { /* ignore */ }
        }
        if (p.street) formData.street = p.street
        if (p.address_detail) formData.addressDetail = p.address_detail
      }
    } catch { /* ignore */ }
  }
})

const scheduleAiSuggest = () => {
  clearTimeout(suggestTimer)
  if (formData.content.length >= 10) {
    suggestTimer = setTimeout(fetchAiSuggest, 800)
  }
}

const fetchAiSuggest = async () => {
  const text = formData.content.trim()
  if (text.length < 5) return
  try {
    const data = await getSuggest(text)
    aiSuggestion.suggested_title = data?.suggested_title || ''
    aiSuggestion.completion = data?.completion || ''
    aiSuggestion.missing_elements = data?.missing_elements || []
  } catch { /* 静默失败 */ }
}

const handleFileChange = (file, files) => { fileList.value = files }
const handleFileRemove = (file, files) => { fileList.value = files }

const handleSubmit = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请完善表单信息')
    return
  }

  submitting.value = true
  try {
    const attachmentIds = []
    for (const file of fileList.value) {
      if (!file.raw) continue
      // 大小校验：跳过超限文件并提示（与上传区文案一致：单张不超过 10MB）
      if (file.raw.size > 10 * 1024 * 1024) {
        ElMessage.warning(`附件「${file.name}」超过10MB，已跳过`)
        continue
      }
      try {
        const data = await uploadAttachment(file.raw, 'appeal_attachment')
        if (data?.file_id) attachmentIds.push(data.file_id)
      } catch {
        // 上传失败必须让用户感知，避免"以为带上了附件、实际丢失"
        ElMessage.error(`附件「${file.name}」上传失败，该附件将不会随诉求提交`)
      }
    }

    const result = await submitAppeal({
      category: selectedCategory.value,
      content: formData.content,
      province: '河北省',
      city: '秦皇岛市',
      district: formData.district || '',
      street: formData.street || '',
      community: '',
      address_detail: formData.addressDetail || '',
      broad_flag: false,
      attachment_ids: attachmentIds
    })

    isSensitive.value = !!result.sensitive
    submittedAppealNo.value = result.appeal_no || ''
    successDialog.value = true
    resetForm()
  } catch { /* 由拦截器处理 */ }
  finally { submitting.value = false }
}

const resetForm = () => {
  formRef.value?.resetFields()
  // 回收本地预览 blob URL
  fileList.value.forEach(f => { if (f.url && f.url.startsWith('blob:')) URL.revokeObjectURL(f.url) })
  fileList.value = []
  aiSuggestion.suggested_title = ''
  aiSuggestion.completion = ''
  aiSuggestion.missing_elements = []
  selectedCategory.value = 'OTHER'
  formData.districtCode = ''
  formData.street = ''
  streets.value = []
}
</script>

<style scoped>
/* 提示条 */
.tips-box { margin-bottom: 14px; border-radius: 6px; }

/* 主表单卡片 */
.form-card {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ececec;
  padding: 20px 22px;
}
.form-section { margin-bottom: 28px; }
.form-section:last-of-type { margin-bottom: 20px; }

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #18181b;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-title .num {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #18181b;
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 诉求类型网格 */
.category-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.category-option {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 14px 6px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  background: #fff;
}
.category-option:hover { border-color: #18181b; background: #fafafa; }
.category-option.active { border-color: #71717a; background: #f4f4f2; color: #18181b; }
.category-option.active .category-icon { color: #18181b; }
.category-icon { font-size: 24px; display: block; margin-bottom: 6px; color: #52525b; }
.category-name { font-size: 13px; }
.hint { margin: 12px 0 0; font-size: 12px; color: #8e8e93; }

/* AI 建议提示 */
.ai-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 12px;
  background: #f4f4f2;
  border-radius: 6px;
  font-size: 13px;
  color: #52525b;
}
.ai-tip.warning { background: #fdf6ec; color: #e6a23c; }
.ai-tip .el-button { margin-left: auto; font-size: 12px; }

/* 联系方式 */
.contact-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  font-size: 14px;
  color: #52525b;
  flex-wrap: wrap;
}
.contact-info strong { color: #18181b; }
.contact-tip { width: 100%; font-size: 12px; color: #8e8e93; margin-left: 30px; }

/* 上传区 */
.upload-trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #8e8e93;
}
.upload-text { font-size: 13px; }
.upload-hint { font-size: 12px; }

/* 提交按钮（内容区内，不再 fixed） */
.submit-bar {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px solid #f4f4f2;
  margin-top: 8px;
}
.submit-bar .el-button { min-width: 120px; }

/* 成功弹窗 */
.success-content { text-align: center; padding: 16px 0; }
.success-icon {
  width: 64px; height: 64px; margin: 0 auto 16px;
  background: #67c23a; color: #fff; font-size: 36px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.success-title { font-size: 18px; font-weight: 600; color: #18181b; margin-bottom: 16px; }
.success-info {
  background: #fafafa; border-radius: 6px; padding: 16px; margin-bottom: 24px;
}
.success-info p { margin: 0 0 8px; font-size: 14px; color: #52525b; }
.success-info p:last-child { margin-bottom: 0; }
.success-tip { font-size: 12px !important; }
.success-info strong { color: #18181b; font-size: 16px; }
.success-actions { display: flex; gap: 12px; justify-content: center; }
.success-actions .el-button { min-width: 100px; }

/* Element Plus 样式覆盖 */
:deep(.el-form-item__label) { font-weight: 500; color: #18181b; }
:deep(.el-textarea__inner) { font-size: 14px; line-height: 1.6; }
:deep(.el-upload--picture-card) {
  width: 100px; height: 100px; border-radius: 8px;
  border-color: #d9d9d9; background: transparent;
}
:deep(.el-upload--picture-card:hover) { border-color: #18181b; }
:deep(.el-upload-list--picture-card) { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
