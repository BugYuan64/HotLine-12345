<!--
  市民工单进度查询与反馈（API 11.4 章）
  - 诉求编号 + 手机号核验身份
  - 诉求进度（管理端→部门端→街道办→办结）：流转时间线已按需求移除，只保留诉求进度
  - 处置与评价：街道办处置完成后自动展示处置结果（含处置方案），下方为评价区域；
    评价后诉求状态变为「已评价」，点击可查看对应评价内容
-->
<template>
  <div class="progress-page">
    <header class="top-bar">
      <div class="logo">12345 政务热线 · 市民端</div>
      <div class="user-area">
        <el-button link @click="goHome">返回首页</el-button>
        <span class="masked">{{ auth.maskedPhone }}</span>
        <el-button link @click="handleLogout">退出登录</el-button>
      </div>
    </header>

    <main class="content">
      <!-- 查询区 -->
      <el-card shadow="never" class="query-card">
        <div class="card-title">诉求进度查询</div>
        <el-form inline @submit.prevent>
          <el-form-item label="诉求编号">
            <el-input
              v-model="query.appealNo"
              placeholder="如：AP20260821123456"
              style="width: 220px"
              clearable
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <!-- 已登录时无需预留手机号：后端以登录账号手机号自动核验，输入框隐藏 -->
          <el-form-item v-if="!auth.token" label="预留手机号">
            <el-input
              v-model="query.phone"
              placeholder="11位手机号"
              style="width: 160px"
              clearable
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
            <el-button @click="resetQuery">清空</el-button>
            <el-button @click="goBack">返回上一界面</el-button>
          </el-form-item>
        </el-form>
        <div class="muted">{{ auth.token ? '已登录：输入诉求编号即可查询（系统以登录手机号自动核验身份）' : '通过诉求编号 + 预留手机号核验身份，可查看处理进度、处置结果并评价' }}</div>
      </el-card>

      <template v-if="queried">
        <!-- 结果区顶部返回入口：方便查看进度后直接回上一界面 -->
        <div class="result-back">
          <el-button link type="primary" @click="goBack">← 返回上一界面</el-button>
        </div>
        <!-- 诉求信息（提交时填写的内容快照：编号/时间/类别/地址/内容/附件），位于进度流转上方 -->
        <el-card v-if="appealInfo" shadow="never" class="block-card">
          <div class="card-title">诉求信息</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="诉求编号">{{ appealInfo.appeal_no || '-' }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ appealInfo.create_time || '-' }}</el-descriptions-item>
            <el-descriptions-item label="类别">{{ categoryLabel(appealInfo.category) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="地址">{{ appealInfo.region || '全市范围' }}</el-descriptions-item>
            <el-descriptions-item label="诉求内容">{{ appealInfo.content || '-' }}</el-descriptions-item>
            <el-descriptions-item label="附件">
              <AttachmentGrid v-if="(appealInfo.attachments || []).length" :files="appealInfo.attachments" />
              <span v-else>-</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
        <!-- 诉求级流程进度（管理端→部门端→街道办→办结；区别于工单时间线） -->
        <el-card v-if="stageData" shadow="never" class="block-card">
          <div class="card-title">诉求进度</div>
          <el-steps :active="stageData.current" align-center finish-status="success">
            <el-step
              v-for="(s, i) in (stageData.stages || [])"
              :key="i"
              :title="s.label"
              :status="s.status === 'done' ? 'success' : (s.status === 'active' ? 'process' : 'wait')"
              :description="[
                s.detail,
                s.time ? formatTime(s.time) : ''
              ].filter(Boolean).join('\n')"
            />
          </el-steps>
        </el-card>

        <!-- 处置与评价：按「各子诉求」分组展示；流转时间线已移除，街道办处置完成后自动展示处置结果，下方为评价区域 -->
        <el-card shadow="never" class="block-card">
          <div class="card-title">处置与评价</div>
          <template v-if="groups.length">
            <div v-for="(g, gi) in groups" :key="gi" class="tl-group">
              <div class="tl-group-head">
                <el-tag v-if="isSplit(g)" size="small" type="success" effect="plain">拆分诉求</el-tag>
                <strong class="tl-group-title">{{ g.title }}</strong>
                <span class="tl-group-no">{{ g.appeal_no || '' }}</span>
                <!-- 已评价：点击展开/收起下方评价内容 -->
                <el-tag v-if="g.evaluated" size="small" type="success" class="clickable-tag" @click="toggleEvalView(g.appeal_no)">已评价</el-tag>
                <el-tag v-else-if="canEvaluateGroup(g)" size="small" type="warning">可评价</el-tag>
                <el-tag v-else-if="childStatusText(g)" size="small" type="info" effect="plain">{{ childStatusText(g) }}</el-tag>
              </div>

              <!-- 街道办处置完成后：直接展示处置结果（自动加载；加载失败可点击重试） -->
              <div v-if="canViewResult(g)" class="group-result">
                <el-divider content-position="left">处置结果</el-divider>
                <el-descriptions v-if="resultMap[g.appeal_no]" :column="2" border size="small">
                  <el-descriptions-item label="处置结果">{{ resultMap[g.appeal_no].dispose_summary || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="处置方案">{{ resultMap[g.appeal_no].dispose_plan || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="处置部门">{{ resultMap[g.appeal_no].department || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="承办街道办">{{ resultMap[g.appeal_no].street_office || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="处置完成时间">{{ formatTime(resultMap[g.appeal_no].dispose_time) || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="办结时间">{{ formatTime(resultMap[g.appeal_no].close_time) || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="处置凭证" :span="2">
                    <template v-if="(resultMap[g.appeal_no].dispose_attachment_urls || []).length">
                      <el-link
                        v-for="(url, i) in resultMap[g.appeal_no].dispose_attachment_urls"
                        :key="i"
                        type="primary"
                        :href="contentUrlOfRaw(url)"
                        target="_blank"
                        style="margin-right: 12px"
                      >{{ (resultMap[g.appeal_no].dispose_attachments || [])[i] || ('凭证' + (i + 1)) }}</el-link>
                    </template>
                    <span v-else>-</span>
                  </el-descriptions-item>
                </el-descriptions>
                <el-button v-else link type="primary" :loading="resultLoading[g.appeal_no]" @click="loadGroupResult(g)">查看处置结果</el-button>
              </div>
              <!-- 尚未处置完成：简要提示 -->
              <div v-else class="muted">街道办处置完成后可查看处置结果并进行评价</div>

              <!-- 评价区域：未评价展示表单；已评价点击查看评价内容 -->
              <template v-if="canViewResult(g) && canEvaluateGroup(g)">
                <div class="group-evaluate">
                  <el-divider content-position="left">满意度评价（处置完成后 7 天内）</el-divider>
                  <el-form label-width="90px" class="evaluate-form" @submit.prevent>
                    <el-form-item label="评价等级" required>
                      <el-radio-group v-model="evalForms[g.appeal_no].label">
                        <el-radio value="VERY_SATISFIED">非常满意</el-radio>
                        <el-radio value="SATISFIED">满意</el-radio>
                        <el-radio value="BASIC">基本满意</el-radio>
                        <el-radio value="DISSATISFIED">不满意</el-radio>
                        <el-radio value="VERY_DISSATISFIED">非常不满意</el-radio>
                      </el-radio-group>
                    </el-form-item>
                    <el-form-item label="评分">
                      <el-rate v-model="evalForms[g.appeal_no].score" />
                    </el-form-item>
                    <el-form-item label="文字说明">
                      <el-input
                        v-model="evalForms[g.appeal_no].remark"
                        type="textarea"
                        :rows="2"
                        maxlength="200"
                        show-word-limit
                        placeholder="选填，200字以内"
                      />
                    </el-form-item>
                    <el-form-item>
                      <el-button type="primary" :loading="savingEval === g.appeal_no" @click="handleEvaluate(g)">提交评价</el-button>
                    </el-form-item>
                  </el-form>
                  <div class="muted">评价提交后不可修改；处置完成后 7 天内未评价，部门将自动审核</div>
                </div>
              </template>
              <template v-else-if="canViewResult(g) && g.evaluated && g.evaluate">
                <div class="group-evaluate">
                  <el-divider content-position="left">满意度评价</el-divider>
                  <el-button link type="primary" @click="toggleEvalView(g.appeal_no)">
                    {{ evalViewVisible[g.appeal_no] ? '收起评价内容' : '查看评价内容' }}
                  </el-button>
                  <el-descriptions v-if="evalViewVisible[g.appeal_no]" :column="1" border size="small" class="eval-desc">
                    <el-descriptions-item label="评价等级">{{ EVAL_LABELS[g.evaluate.label] || g.evaluate.label }}</el-descriptions-item>
                    <el-descriptions-item label="评分">
                      <el-rate :model-value="g.evaluate.score" disabled />
                    </el-descriptions-item>
                    <el-descriptions-item label="文字说明">{{ g.evaluate.remark || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="评价时间">{{ g.evaluate.evaluate_time || '-' }}</el-descriptions-item>
                  </el-descriptions>
                </div>
              </template>

              <!-- 已评价且不满意/非常不满意 → 可申请重开（仅未申请过时展示表单） -->
              <div v-if="g.evaluated && g.evaluate && ['DISSATISFIED', 'VERY_DISSATISFIED'].includes(g.evaluate.label) && !g.evaluate.reopen_status" class="group-reopen">
                <el-divider content-position="left">申请重新处理</el-divider>
                <el-form label-width="90px" @submit.prevent>
                  <el-form-item label="补充说明" required>
                    <el-input
                      v-model="g.reopenReason"
                      type="textarea"
                      :rows="2"
                      maxlength="500"
                      show-word-limit
                      placeholder="请说明希望重新处理的理由（必填，500字以内）"
                    />
                  </el-form-item>
                  <el-form-item>
                    <el-button type="warning" :loading="savingEval === g.appeal_no" @click="handleReopen(g)">提交重开申请</el-button>
                  </el-form-item>
                </el-form>
                <div class="muted">重开申请由管理员审批，每个工单最多重开 1 次，审批结果将通过消息通知</div>
              </div>
              <!-- 已提交/已审批重开申请的状态提示（含 APPROVED/REJECTED） -->
              <div v-else-if="g.evaluate && g.evaluate.reopen_status" class="group-reopen">
                <el-divider content-position="left">申请重新处理</el-divider>
                <el-result
                  icon="warning"
                  :title="g.evaluate.reopen_status === 'PENDING' ? '已提交重开申请' : (g.evaluate.reopen_status === 'APPROVED' ? '重开申请已通过' : '重开申请已驳回')"
                  :sub-title="g.evaluate.reopen_status === 'PENDING' ? '请等待管理员审批，审批结果将通过消息通知' : '重开申请审批完成，如需协助请联系热线客服'"
                />
              </div>
            </div>
          </template>
          <el-empty v-else description="暂无处置与评价记录" :image-size="60" />
        </el-card>
      </template>

      <el-empty v-else :description="auth.token ? '输入诉求编号查询进度' : '输入诉求编号与预留手机号开始查询'" />
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AttachmentGrid, { contentUrlOfRaw } from '../../shared/AttachmentGrid.vue'
import { useAuthStore } from '../stores/auth'
import { logout } from '../api/auth'
import { getResult, submitEvaluate, applyReopen, getFeedbackTimeline, getAppealStage } from '../api/feedback'
import { getPrimaries } from '../api/appeal'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

// 查询表单（从列表页/首页跳转携带的 appealNo 自动预填）
const query = reactive({ appealNo: '', phone: '' })
const loading = ref(false)
const queried = ref(false)

// 查询结果
const appealInfo = ref(null) // 诉求信息（内容快照 + 附件），来自 timeline 响应
const stageData = ref(null)
const groups = ref([])
const evalForms = reactive({}) // key=子诉求编号，表单独立管理支持逐个评价
const savingEval = ref('')     // 正在提交的诉求编号
const resultMap = reactive({})     // key=子诉求编号 -> 处置结果（懒加载）
const resultLoading = reactive({})

// 类别代码 → 中文名（classify_primary.name，如 URBAN → 城市管理）
const catMap = ref({})
const categoryLabel = (c) => catMap.value[c] || c || '-'

// 评价等级代码 → 中文（评价内容展示用）
const EVAL_LABELS = {
  VERY_SATISFIED: '非常满意',
  SATISFIED: '满意',
  BASIC: '基本满意',
  DISSATISFIED: '不满意',
  VERY_DISSATISFIED: '非常不满意'
}

// 已评价诉求的「查看评价内容」展开状态（key=诉求编号）
const evalViewVisible = reactive({})
const toggleEvalView = (no) => { evalViewVisible[no] = !evalViewVisible[no] }

// 是否拆分出的子诉求（编号与父诉求不同 → 拆分子诉求；相同 → 未拆分诉求直挂工单）
const isSplit = (g) => !!g.appeal_no && !!appealInfo.value?.appeal_no && g.appeal_no !== appealInfo.value.appeal_no

// 未处置完成子诉求的环节提示（流转时间线移除后以标签简要展示）
const childStatusText = (g) => {
  switch (g.order_status) {
    case null: case undefined: case '': return 'AI 处理中'
    case 'PENDING_DISPATCH': case 'PENDING_REASSIGN': case 'DEPT_REJECTED': return '管理端处理中'
    case 'PENDING_DEPT_PROCESS': case 'PENDING_DEPT_ASSIGN': case 'STREET_REJECTED': case 'PENDING_UPDATE': return '部门端处理中'
    case 'PENDING_STREET_PROCESS': return '街道办处理中'
    default: return ''
  }
}

onMounted(async () => {
  try {
    const list = await getPrimaries()
    catMap.value = Object.fromEntries((list || []).map((p) => [p.code, p.name]))
  } catch { catMap.value = {} }
  if (route.query.appealNo) {
    query.appealNo = String(route.query.appealNo)
    // 从提交成功页「去查看」跳转：登录态自动展示该诉求进度
    if (auth.token) {
      handleQuery().then(() => {
        if (!queried.value) ElMessage.warning('进度加载失败，请稍后重试或点击查询')
      })
    }
  }
})

// 子诉求工单处置完成后即可评价（含新流程待市民评价/存量已处理，排除已评价/归档）
const EVALUABLE_STATUS = ['PENDING_EVALUATE', 'STREET_DONE', 'CLOSED']
const canEvaluateGroup = (g) => EVALUABLE_STATUS.includes(g.order_status) && !g.evaluated

// 子诉求工单已完成处置（展示处置结果与评价区域）
const RESULT_STATUS = ['PENDING_EVALUATE', 'STREET_DONE', 'EVALUATED', 'DEPT_AUDITED', 'CLOSED', 'ARCHIVED']
const canViewResult = (g) => RESULT_STATUS.includes(g.order_status)

// 懒加载指定子诉求的处置结果
const loadGroupResult = async (g) => {
  if (resultMap[g.appeal_no] || resultLoading[g.appeal_no]) return
  resultLoading[g.appeal_no] = true
  try {
    const r = await getResult(query.appealNo.trim(), g.appeal_no, query.phone.trim(), { skipErrorMessage: true })
    resultMap[g.appeal_no] = r || {}
  } catch {
    resultMap[g.appeal_no] = null
  } finally {
    resultLoading[g.appeal_no] = false
  }
}

// 返回上一界面：有浏览历史则回退，否则兜底回首页
const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/home')
}
const goHome = () => router.push('/home')

const handleLogout = async () => {
  try { await logout() } catch (e) { /* 网络异常也继续本地清理 */ }
  auth.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

const resetQuery = () => {
  query.appealNo = ''
  query.phone = ''
  queried.value = false
  appealInfo.value = null
  stageData.value = null
  groups.value = []
  Object.keys(evalForms).forEach((k) => delete evalForms[k])
  Object.keys(resultMap).forEach((k) => delete resultMap[k])
  Object.keys(evalViewVisible).forEach((k) => delete evalViewVisible[k])
}

// 查询进度（诉求信息 + 诉求级阶段 + 各子诉求处置与评价）
const handleQuery = async () => {
  if (!query.appealNo.trim()) {
    ElMessage.warning('请输入诉求编号')
    return
  }
  // 登录态 phone 可留空（后端以登录账号手机号核验）；填写时须为 11 位数字
  if (query.phone.trim() && !/^1[3-9]\d{9}$/.test(query.phone.trim())) {
    ElMessage.warning('请输入正确的11位手机号')
    return
  }

  loading.value = true
  queried.value = false
  appealInfo.value = null
  stageData.value = null
  groups.value = []
  Object.keys(evalForms).forEach((k) => delete evalForms[k])
  Object.keys(resultMap).forEach((k) => delete resultMap[k])
  Object.keys(evalViewVisible).forEach((k) => delete evalViewVisible[k])
  try {
    // 诉求级流程进度（管理端→部门端→街道办→办结，任何状态都可查）
    try {
      stageData.value = await getAppealStage(query.appealNo.trim(), query.phone.trim())
    } catch { stageData.value = null }

    // 分组数据（拆分后按父诉求+各子诉求分组；流转时间线已移除，仅保留各子诉求分组；附带诉求信息快照）
    const tl = await getFeedbackTimeline(query.appealNo.trim(), query.phone.trim())
    appealInfo.value = tl.appeal || null
    groups.value = (tl.groups || []).filter((g) => g.scope === 'CHILD')
    // 初始化各子诉求评价表单（可评价的子诉求）
    groups.value.forEach((g) => {
      if (!evalForms[g.appeal_no]) {
        evalForms[g.appeal_no] = { score: 4, label: 'SATISFIED', remark: '' }
      }
      if (!g.reopenReason) g.reopenReason = ''
    })
    // 街道办处置完成的子诉求自动加载处置结果（无需再点击「查看处置结果」）
    groups.value.filter((g) => canViewResult(g)).forEach((g) => loadGroupResult(g))

    queried.value = true
  } catch { /* 业务异常（诉求不存在/手机号不一致等）由拦截器统一提示 */ }
  finally {
    loading.value = false
  }
}

// 提交满意度评价（按子诉求单独评价，透传 child_appeal_id）
const handleEvaluate = async (g) => {
  const form = evalForms[g.appeal_no]
  if (!form || !form.label) {
    ElMessage.warning('请选择评价等级')
    return
  }
  savingEval.value = g.appeal_no
  try {
    await submitEvaluate(query.appealNo.trim(), {
      phone: query.phone.trim(),
      child_appeal_id: g.appeal_no,
      score: form.score,
      label: form.label,
      remark: form.remark || undefined
    })
    ElMessage.success('评价提交成功，感谢您的反馈')
    // 刷新时间线（后端返回该子诉求已评价，隐藏评价表单）
    await handleQuery()
  } catch { /* 拦截器已提示 */ } finally {
    savingEval.value = ''
  }
}

// 提交重开申请（针对指定子诉求）
const handleReopen = async (g) => {
  if (!g.reopenReason || !g.reopenReason.trim()) {
    ElMessage.warning('请填写重开补充说明')
    return
  }
  savingEval.value = g.appeal_no
  try {
    await applyReopen(query.appealNo.trim(), {
      phone: query.phone.trim(),
      child_appeal_id: g.appeal_no,
      reopen_reason: g.reopenReason.trim()
    })
    ElMessage.success('重开申请已提交，等待管理员审批')
    // 刷新时间线（后端返回 reopen_status=PENDING，前端显示已申请状态）
    await handleQuery()
  } catch { /* 拦截器已提示 */ } finally {
    savingEval.value = ''
  }
}
</script>

<style scoped>
.progress-page { min-height: 100%; background: var(--el-bg-color-page); }
.top-bar {
  height: var(--app-header-height);
  background: linear-gradient(135deg, var(--el-color-primary), var(--app-accent));
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--app-space-xl);
  border-bottom: none;
  box-shadow: var(--app-shadow-sm);
}
.logo { color: #fff; font-size: 18px; font-weight: 700; letter-spacing: 0.5px; }
.user-area { display: flex; align-items: center; gap: var(--app-space-sm); }
.top-bar :deep(.el-button) { color: #fff !important; }
.top-bar :deep(.el-button:hover) { color: rgba(255, 255, 255, 0.85) !important; }
.masked { color: rgba(255, 255, 255, 0.9); font-size: 14px; }
.content { max-width: 860px; margin: var(--app-space-lg) auto var(--app-space-xl); padding: 0 var(--app-space-md); }
.query-card { margin-bottom: var(--app-space-md); }
.block-card { margin-bottom: var(--app-space-md); }
.card-title { font-size: 16px; font-weight: 600; color: var(--el-color-primary); margin-bottom: var(--app-space-sm); }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 6px; }
.result-back { margin-bottom: var(--app-space-sm); }
.evaluate-form { max-width: 560px; }
.tl-group { margin-bottom: 16px; }
.tl-group:last-child { margin-bottom: 0; }
.tl-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.tl-group-title { font-size: 14px; }
.tl-group-no { color: var(--el-text-color-secondary); font-size: 12px; }
.clickable-tag { cursor: pointer; }
.group-evaluate { padding: 4px 8px 0; }
.group-reopen { padding: 4px 8px 0; }
.group-result { padding: 4px 8px 0; }
.eval-desc { margin-top: 8px; max-width: 560px; }
</style>