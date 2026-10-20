<!--
  诉求进度弹窗（办理进度模块）：
  - 点列表卡片/跳转携带 appealNo 时打开，右上角 X 关闭（el-dialog 默认 show-close）
  - 内容：诉求进度步骤条 + 处置与评价（按「各子诉求」分组；流转时间线已移除）；
    街道办处置完成后自动展示处置结果，下方为评价区域，已评价点击可查看评价内容
  - 登录态 phone 传空，后端以登录账号手机号自动核验
-->
<template>
  <el-dialog
    v-model="visible"
    :title="`诉求进度（${appealNo || ''}）`"
    width="min(680px, 92vw)"
    :close-on-click-modal="false"
    destroy-on-close
    align-center
    @closed="handleClosed"
  >
    <div v-loading="loading" class="dialog-body">
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
            <!-- 已提交重开申请的状态提示 -->
            <div v-else-if="g.evaluate && g.evaluate.reopen_status === 'PENDING'" class="group-reopen">
              <el-divider content-position="left">申请重新处理</el-divider>
              <div class="muted">已提交重开申请，请等待管理员审批</div>
            </div>
          </div>
        </template>
        <el-empty v-else description="暂无处置与评价记录" :image-size="60" />
      </el-card>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { ElMessage } from 'element-plus'
import AttachmentGrid, { contentUrlOfRaw } from '../../shared/AttachmentGrid.vue'
import { getResult, submitEvaluate, applyReopen, getFeedbackTimeline, getAppealStage } from '../api/feedback'
import { getPrimaries } from '../api/appeal'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  appealNo: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const appealInfo = ref(null)   // 诉求信息（内容快照 + 附件），来自 timeline 响应
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
})

// 子诉求工单处置完成后即可评价（新流程待市民评价/存量已处理，排除已评价/归档）
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
    const r = await getResult(props.appealNo.trim(), g.appeal_no, '', { skipErrorMessage: true })
    resultMap[g.appeal_no] = r || {}
  } catch {
    resultMap[g.appeal_no] = null
  } finally {
    resultLoading[g.appeal_no] = false
  }
}

// 加载进度（诉求信息 + 诉求级阶段 + 各子诉求处置与评价）
const loadData = async () => {
  if (!props.appealNo) return
  loading.value = true
  appealInfo.value = null
  stageData.value = null
  groups.value = []
  Object.keys(evalForms).forEach((k) => delete evalForms[k])
  Object.keys(resultMap).forEach((k) => delete resultMap[k])
  Object.keys(evalViewVisible).forEach((k) => delete evalViewVisible[k])
  try {
    // 诉求级流程进度（任何状态都可查；登录态 phone 空，后端自动核验）
    try {
      stageData.value = await getAppealStage(props.appealNo.trim(), '')
    } catch { stageData.value = null }

    // 分组数据（拆分后按父诉求+各子诉求分组；流转时间线已移除，仅保留各子诉求分组；附带诉求信息快照）
    const tl = await getFeedbackTimeline(props.appealNo.trim(), '')
    appealInfo.value = tl.appeal || null
    groups.value = (tl.groups || []).filter((g) => g.scope === 'CHILD')
    groups.value.forEach((g) => {
      if (!evalForms[g.appeal_no]) {
        evalForms[g.appeal_no] = { score: 4, label: 'SATISFIED', remark: '' }
      }
      if (!g.reopenReason) g.reopenReason = ''
    })
    // 街道办处置完成的子诉求自动加载处置结果（无需再点击「查看处置结果」）
    groups.value.filter((g) => canViewResult(g)).forEach((g) => loadGroupResult(g))
  } catch { /* 业务异常由拦截器统一提示 */ }
  finally {
    loading.value = false
  }
}

// 打开弹窗且有诉求编号时加载
watch(
  () => [props.modelValue, props.appealNo],
  ([open, no]) => {
    if (open && no) loadData()
  }
)

// 提交满意度评价（按子诉求单独评价，透传 child_appeal_id）
const handleEvaluate = async (g) => {
  const form = evalForms[g.appeal_no]
  if (!form || !form.label) {
    ElMessage.warning('请选择评价等级')
    return
  }
  savingEval.value = g.appeal_no
  try {
    await submitEvaluate(props.appealNo.trim(), {
      phone: '',
      child_appeal_id: g.appeal_no,
      score: form.score,
      label: form.label,
      remark: form.remark || undefined
    })
    ElMessage.success('评价提交成功，感谢您的反馈')
    // 刷新时间线（后端返回该子诉求已评价）
    await loadData()
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
    await applyReopen(props.appealNo.trim(), {
      phone: '',
      child_appeal_id: g.appeal_no,
      reopen_reason: g.reopenReason.trim()
    })
    ElMessage.success('重开申请已提交，等待管理员审批')
    // 刷新时间线（后端返回 reopen_status=PENDING，前端显示已申请状态）
    await loadData()
  } catch { /* 拦截器已提示 */ } finally {
    savingEval.value = ''
  }
}

// 关闭后重置会话内状态（destroy-on-close 已卸载表单，此处兜底清理）
const handleClosed = () => {
  Object.keys(evalForms).forEach((k) => delete evalForms[k])
  Object.keys(resultMap).forEach((k) => delete resultMap[k])
  Object.keys(evalViewVisible).forEach((k) => delete evalViewVisible[k])
}
</script>

<style scoped>
.dialog-body { min-height: 120px; }
.block-card { margin-bottom: 14px; }
.block-card:last-child { margin-bottom: 0; }
.card-title { font-size: 15px; font-weight: 600; color: var(--el-color-primary); margin-bottom: 10px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 6px; }
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