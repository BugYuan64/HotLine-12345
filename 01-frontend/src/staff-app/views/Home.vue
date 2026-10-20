<template>
  <!-- 需求 2.4 部门人员端：部门工作台首页（指标卡/待办列表，P0-6 填充） -->
  <StaffLayout title="部门工作台">
      <div class="page-body">
        <!-- 指标卡（仅本部门工单统计） -->
        <el-row :gutter="16" class="metric-row mb-md">
          <el-col :span="8">
            <div class="metric-card clickable" @click="router.push('/staff/orders')">
              <div class="metric-label">工单总数</div>
              <div class="metric-value">{{ wb.order_total }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="metric-card">
              <div class="metric-label">本月已办</div>
              <div class="metric-value">{{ wb.month_done }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="metric-card">
              <div class="metric-label">平均评分</div>
              <div class="metric-value">{{ wb.avg_score }}</div>
            </div>
          </el-col>
        </el-row>

        <!-- 待我介入：需部门推进的待办事项（推送类通知由站内消息留存，不在界面展示） -->
        <el-card shadow="never" class="todo-panel mb-md">
          <div class="todo-title">待我介入</div>
          <div class="todo-row">
            <div class="todo-card" :class="{ active: (wb.pending_verify || 0) > 0 }" @click="router.push('/staff/orders?status=PENDING_DEPT_PROCESS')">
              <div class="todo-label">待核验工单</div>
              <div class="todo-num">{{ wb.pending_verify || 0 }}</div>
            </div>
            <div class="todo-card" :class="{ active: (wb.pending_assign || 0) > 0 }" @click="router.push('/staff/orders?status=PENDING_DEPT_ASSIGN')">
              <div class="todo-label">待分派工单</div>
              <div class="todo-num">{{ wb.pending_assign || 0 }}</div>
            </div>
            <div class="todo-card" :class="{ active: (wb.pending_audit || 0) > 0 }" @click="router.push('/staff/orders?tab=audit')">
              <div class="todo-label">待审核工单</div>
              <div class="todo-num">{{ wb.pending_audit || 0 }}</div>
            </div>
            <div class="todo-card" :class="{ active: (wb.pending_delay || 0) > 0 }" @click="router.push('/staff/orders?tab=delay')">
              <div class="todo-label">待审批延期</div>
              <div class="todo-num">{{ wb.pending_delay || 0 }}</div>
            </div>
            <div class="todo-card" :class="{ active: warnCount > 0 }" @click="router.push('/staff/warnings')">
              <div class="todo-label">待跟进预警</div>
              <div class="todo-num">{{ warnCount }}</div>
            </div>
          </div>
        </el-card>

        <!-- 核心入口 -->
        <el-row :gutter="16" class="entry-row">
          <el-col :span="12">
            <div class="entry-card" @click="router.push('/staff/orders')">
              <el-icon class="entry-icon blue" :size="32"><Document /></el-icon>
              <div class="entry-title">工单审核</div>
              <div class="entry-desc">本分类全部辖区工单的核验与分派</div>
              <div class="entry-arrow">→</div>
            </div>
          </el-col>

        </el-row>

        <!-- 本部门诉求趋势折线图（最多5条：本部门一级总括/二级分类 × 区县 × 近7/15/30天，数据范围锁定本部门） -->
        <el-card shadow="never" class="trend-panel mb-md">
          <div class="trend-title">本部门诉求趋势</div>
          <el-form inline class="trend-filters">
            <!-- 二级分类：一级分类由登录部门自动确定，选「全部」= 本部门一级总括数据 -->
            <el-form-item label="二级分类">
              <el-select v-model="trendForm.sub" style="width: 170px">
                <el-option label="全部（本分类总括）" value="" />
                <el-option v-for="s in trendSubOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <!-- 区县筛选：只到区县级（不下钻街道），不选 = 全部区县 -->
            <el-form-item label="区县">
              <el-select v-model="trendForm.region" clearable placeholder="全部区县" style="width: 130px">
                <el-option v-for="r in trendRegionOptions" :key="r" :label="r" :value="r" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间">
              <el-select v-model="trendForm.days" style="width: 105px">
                <el-option label="近7天" :value="7" />
                <el-option label="近15天" :value="15" />
                <el-option label="近30天" :value="30" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="trendAdding" @click="addTrendLine">
                <el-icon class="trend-btn-icon"><Plus /></el-icon>添加折线
              </el-button>
            </el-form-item>
          </el-form>
          <!-- 已添加的折线（彩色圆点标识，可关闭移除） -->
          <div class="trend-lines" v-if="trendLines.length">
            <el-tag v-for="(l, i) in trendLines" :key="l.key" closable effect="plain" class="trend-line-tag"
              @close="removeTrendLine(i)">
              <span class="trend-dot" :style="{ background: lineColor(i) }"></span>{{ l.name }}
            </el-tag>
          </div>
          <div v-if="!trendLines.length" class="trend-empty">
            选择二级分类、区县与时间后点击「添加折线」：「全部」展示本部门分类总括趋势，选具体二级展示细分趋势（最多 5 条）
          </div>
          <div v-show="trendLines.length" ref="trendChartRef" class="trend-chart"></div>
        </el-card>

        <!-- 操作指引 -->
        <el-card shadow="never" class="guide-card">
          <template #header><span class="guide-title"><el-icon :size="16"><Flag /></el-icon> 操作指引</span></template>
          <ul class="guide-list">
            <li>对「待部门分派」工单进行归属核验后分派至对应街道办</li>
            <li>街道办提交的处置结果需部门审核通过后流转至全局终审</li>
            <li>右上角「个人中心」可查看个人信息、修改登录密码与登录日志</li>
          </ul>
        </el-card>
      </div>
  </StaffLayout>

  <!-- 个人信息（需求 18(1)：仅查看不可修改） -->
  <el-dialog v-model="profileVisible" title="个人信息" width="460px">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="姓名">{{ auth.userInfo?.name }}</el-descriptions-item>
      <el-descriptions-item label="工号">{{ auth.userInfo?.job_no }}</el-descriptions-item>
      <el-descriptions-item label="归属类型">{{ auth.userType }}</el-descriptions-item>
      <el-descriptions-item label="辖区/部门">{{ auth.userInfo?.region }}</el-descriptions-item>
    </el-descriptions>
  </el-dialog>

  <!-- 修改密码（API 9.10 / 需求 18(2)(3)） -->
  <el-dialog v-model="pwdVisible" title="修改登录密码" width="460px">
    <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="100px">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input v-model="pwdForm.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="pwdForm.newPassword" type="password" show-password
                  placeholder="至少8位，含数字+字母+特殊字符" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="pwdForm.confirmPassword" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdVisible = false">取消</el-button>
      <el-button type="primary" :loading="pwdSaving" @click="handleChangePassword">确认修改</el-button>
    </template>
  </el-dialog>

  <!-- 登录日志（API 9.11 / 需求 18(4)） -->
  <el-dialog v-model="logsVisible" title="登录日志" width="640px">
    <el-table :data="logs" v-loading="logsLoading" size="small">
      <el-table-column prop="login_time" label="登录时间" width="180" />
      <el-table-column prop="login_method" label="登录方式" width="150">
        <template #default="{ row }">{{ row.login_method === 'JOB_NO_PASSWORD' ? '工号密码' : '短信验证码' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="result" label="结果">
        <template #default="{ row }">
          <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup>
// 部门端工作台 + 个人基础功能（需求 18：个人信息查看/登录密码修改/操作日志查看）
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { Document, Checked, Flag, Plus } from '@element-plus/icons-vue'
import StaffLayout from '../components/StaffLayout.vue'
import { useAuthStore } from '../stores/auth'
import { changePassword, loginLogs, logout } from '../api/auth'
import { getUnrespondedCount, getSubCategories } from '../api/warning'
import { getStaffDailyTrend } from '../api/stats'
import { getPrimaries } from '../api/classify'
import { getCounties } from '../api/region'
import request from '../api/request'

const auth = useAuthStore()
const router = useRouter()

// 工作台指标：仅本部门工单统计（pending_verify 待核验 / pending_assign 待分派 / pending_delay 待审批延期）
const wb = ref({ order_total: 0, pending_handle: 0, pending_verify: 0, pending_assign: 0, pending_audit: 0, pending_delay: 0, month_done: 0, avg_score: 0 })
// 待跟进预警：已发送给本部门且响应闭环未完成
const warnCount = ref(0)
const loadWorkbench = async () => {
  try {
    wb.value = await request.get('/staff/workbench')
  } catch (e) {
    // 统计加载失败保持 0
  }
}
const loadWarnCount = async () => {
  try {
    const data = await getUnrespondedCount()
    warnCount.value = data?.unresponded_count || 0
  } catch (e) {
    // 加载失败保持 0
  }
}
onMounted(() => { loadWorkbench(); loadWarnCount() })

// ---- 本部门诉求趋势折线图（最多 5 条：本部门一级总括/二级分类 × 区县 × 近7/15/30天，
//      一级分类由后端按 JWT user_type 锁定，只能看本部门分类） ----
const trendChartRef = ref(null)
let trendChart = null
const MAX_TREND_LINES = 5
const TREND_COLORS = ['#165DFF', '#00B42A', '#FF7D00', '#722ED1', '#F53F3F']
const trendForm = reactive({ sub: '', region: '', days: 15 })   // sub='' 即「全部」（一级总括）；region=区县（空=全部）
const trendLines = ref([])   // 已添加折线 { key, name, sub, region, dates, values, days }
const trendAdding = ref(false)

// 一级分类 code -> 名称（折线命名用，本部门 user_type 即一级分类 code）
const trendPrimaryMap = reactive({})
// 二级分类选项：仅本部门一级分类下已用于工单的二级分类（后端按 user_type 过滤）
const trendSubOptions = ref([])
// 区县选项：只到区县级（不下钻街道）
const trendRegionOptions = ref([])
const loadTrendDict = async () => {
  try {
    (await getPrimaries() || []).forEach((p) => { if (p.code) trendPrimaryMap[p.code] = p.name })
  } catch { /* 忽略，仅影响命名 */ }
  try {
    trendSubOptions.value = (await getSubCategories(auth.userType)) || []
  } catch { /* 忽略，仅影响下拉选项 */ }
  try {
    trendRegionOptions.value = ((await getCounties()) || []).map((c) => c.region_name)
  } catch { /* 忽略，仅影响下拉选项 */ }
}
const deptCategoryLabel = () => trendPrimaryMap[auth.userType] || '本部门分类'

const lineColor = (i) => TREND_COLORS[i % TREND_COLORS.length]

/** 添加一条折线：调 /staff/trends/daily（一级分类由后端锁定为登录部门），拉取序列后渲染 */
const addTrendLine = async () => {
  if (trendLines.value.length >= MAX_TREND_LINES) { ElMessage.warning(`最多同时展示 ${MAX_TREND_LINES} 条折线`); return }
  const sub = trendForm.sub   // 空 = 全部（本部门一级总括）
  const key = `${sub}|${trendForm.region}|${trendForm.days}`
  if (trendLines.value.some((l) => l.key === key)) { ElMessage.warning('该分类与时间的折线已存在'); return }
  trendAdding.value = true
  try {
    const data = await getStaffDailyTrend({ days: trendForm.days, subCategory: sub, region: trendForm.region || '' })
    const name = `${deptCategoryLabel()}${sub ? '-' + sub : '（总括）'}${trendForm.region ? '@' + trendForm.region : ''}·近${trendForm.days}天`
    trendLines.value.push({ key, name, sub, region: trendForm.region || '', dates: data?.dates || [], values: data?.values || [], days: trendForm.days })
    await nextTick()
    renderTrendChart()
  } catch { /* 接口异常由请求拦截器统一提示 */ } finally { trendAdding.value = false }
}

/** 移除一条折线并重绘 */
const removeTrendLine = (i) => {
  trendLines.value.splice(i, 1)
  renderTrendChart()
}

/** 时间窗口变更：已存在折线保持各自二级分类/区县不变，按新天数重新拉取并重绘 */
watch(() => trendForm.days, async (days) => {
  if (!trendLines.value.length) return
  await Promise.all(trendLines.value.map(async (l) => {
    try {
      const data = await getStaffDailyTrend({ days, subCategory: l.sub, region: l.region })
      l.key = `${l.sub}|${l.region}|${days}`
      l.name = `${deptCategoryLabel()}${l.sub ? '-' + l.sub : '（总括）'}${l.region ? '@' + l.region : ''}·近${days}天`
      l.days = days
      l.dates = data?.dates || []
      l.values = data?.values || []
    } catch { /* 单条失败保留原数据 */ }
  }))
  // 时间统一后可能出现重复折线（原仅天数不同的两条），去重保留首条
  const seen = new Set()
  trendLines.value = trendLines.value.filter((l) => {
    if (seen.has(l.key)) return false
    seen.add(l.key)
    return true
  })
  renderTrendChart()
})

/** 渲染对比折线：x 轴取最长时间窗（各线窗口不同时窗口外断开为 null） */
const renderTrendChart = () => {
  if (!trendChartRef.value || !trendLines.value.length) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)
  const maxDays = Math.max(...trendLines.value.map((l) => l.days))
  const base = trendLines.value.find((l) => l.days === maxDays)
  const xAxis = base ? base.dates : []
  const series = trendLines.value.map((l, idx) => ({
    name: l.name,
    type: 'line',
    smooth: true,
    showSymbol: false,
    itemStyle: { color: lineColor(idx) },
    lineStyle: { width: 2 },
    data: xAxis.map((label) => {
      const i = l.dates.indexOf(label)
      return i >= 0 ? l.values[i] : null
    })
  }))
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { top: 0, type: 'scroll' },
    grid: { left: 40, right: 20, top: 36, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: xAxis },
    yAxis: { type: 'value', minInterval: 1, name: '诉求量' },
    series
  }, true)
}

/** 趋势图自适应窗口 + 卸载清理 */
const handleTrendResize = () => { if (trendChart) trendChart.resize() }
onMounted(() => {
  loadTrendDict()
  window.addEventListener('resize', handleTrendResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleTrendResize)
  if (trendChart) { trendChart.dispose(); trendChart = null }
})

const profileVisible = ref(false)
const pwdVisible = ref(false)
const logsVisible = ref(false)

// 修改密码：校验规则与后端一致
const pwdFormRef = ref()
const pwdSaving = ref(false)
const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const PASSWORD_REGEX = /^(?=.*\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$/
const pwdRules = {
  oldPassword: [{ required: true, message: '原密码不能为空', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '新密码不能为空', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!PASSWORD_REGEX.test(value)) {
          callback(new Error('密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '确认新密码不能为空', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.value.newPassword) {
          callback(new Error('两次输入的新密码不匹配'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const logsLoading = ref(false)
const logs = ref([])

const handleCommand = async (command) => {
  if (command === 'profile') {
    profileVisible.value = true
  } else if (command === 'password') {
    pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    pwdVisible.value = true
  } else if (command === 'logs') {
    logsVisible.value = true
    logsLoading.value = true
    try {
      const data = await loginLogs({ page: 1, page_size: 20 })
      logs.value = data.list || []
    } finally {
      logsLoading.value = false
    }
  } else if (command === 'logout') {
    try {
      await logout()
    } catch (e) {
      // 网络异常也继续本地清理
    }
    auth.logout()
    ElMessage.success('已退出登录')
    router.push('/staff/login')
  }
}

const handleChangePassword = async () => {
  await pwdFormRef.value.validate()
  pwdSaving.value = true
  try {
    await changePassword({
      old_password: pwdForm.value.oldPassword,
      new_password: pwdForm.value.newPassword,
      confirm_password: pwdForm.value.confirmPassword
    })
    ElMessage.success('密码修改成功')
    pwdVisible.value = false
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.layout { height: 100%; }
.user-area { display: flex; align-items: center; gap: 8px; color: var(--el-text-color-secondary); font-size: 14px; }
.entry-row { margin-bottom: 16px; }
.entry-card {
  position: relative;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--app-radius-lg);
  padding: 22px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: var(--app-shadow-sm);
}
.entry-card:hover { transform: translateY(-3px); box-shadow: var(--app-shadow-md); border-color: var(--el-color-primary-light-8); }
.entry-icon {
  width: 46px;
  height: 46px;
  border-radius: var(--app-radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  margin-bottom: 12px;
}
.entry-icon.blue { background: var(--el-color-primary-light-9); }
.entry-icon.green { background: var(--el-color-success-light-9); }
.entry-title { font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 6px; }
.entry-desc { font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.5; }
.entry-arrow {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--el-color-primary-light-5);
  font-size: 20px;
}
.guide-card { border-radius: var(--app-radius-lg); }
.guide-title { font-weight: 600; color: var(--el-text-color-primary); }
.guide-list { padding-left: 18px; margin: 0; }
.guide-list li { font-size: 13px; color: var(--el-text-color-regular); line-height: 2; }
.metric-card.clickable { cursor: pointer; transition: all 0.2s; }
.metric-card.clickable:hover { transform: translateY(-2px); box-shadow: var(--app-shadow-md); border-color: var(--el-color-primary-light-8); }
.todo-panel { border-radius: var(--app-radius-lg); }
.todo-title { font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 12px; }
.todo-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.todo-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--app-radius-md);
  padding: 12px 14px;
  cursor: pointer;
  background: #fafafa;
  transition: all 0.2s;
}
.todo-card:hover { transform: translateY(-2px); box-shadow: var(--app-shadow-md); border-color: var(--el-color-primary-light-8); }
.todo-card.active { background: var(--el-color-warning-light-9); border-color: var(--el-color-warning-light-5); }
.todo-label { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.todo-num { font-family: var(--font-number); font-size: 24px; font-weight: 700; color: var(--el-text-color-primary); line-height: 1; }
.todo-card.active .todo-num { color: var(--el-color-warning); }
/* 本部门诉求趋势折线图 */
.trend-panel { border-radius: var(--app-radius-lg); }
.trend-title { font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 12px; }
.trend-filters { margin-bottom: 8px; }
.trend-btn-icon { margin-right: 4px; }
.trend-lines { display: flex; flex-wrap: wrap; margin-bottom: 8px; }
.trend-line-tag { margin: 0 8px 8px 0; }
.trend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.trend-empty { color: var(--el-text-color-secondary); font-size: 13px; padding: 8px 0 4px; }
.trend-chart { width: 100%; height: 320px; }
</style>
