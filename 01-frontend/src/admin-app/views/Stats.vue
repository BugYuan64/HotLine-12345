<template>
  <!-- 全局数据统计（需求 2.3 全局数据统计页）：参考"快进"Dashboard 风格，数据来自后端 summary API -->
  <AdminLayout title="全局数据统计">
    <!-- 筛选条 -->
    <div class="stats-filter">
      <el-select v-model="filters.period" style="width: 150px" @change="loadStats">
        <el-option label="近7天" value="WEEK" />
        <el-option label="近30天" value="MONTH" />
        <el-option label="今日" value="DAY" />
      </el-select>
      <el-select v-model="filters.category" clearable placeholder="全部分类" style="width: 150px" @change="loadStats">
        <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
      </el-select>
      <el-select v-model="filters.region" clearable filterable placeholder="选择辖区" style="width: 180px"
        @change="loadStats" @clear="loadStats">
        <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_name" />
      </el-select>
    </div>

    <!-- 主区：左 Accounts + 中 Spending Plan + 右 Bills & Payments -->
    <div class="stats-grid">
      <!-- 左列：Accounts（可折叠分组） -->
      <div class="col-accounts">
        <div class="panel-header">核心指标</div>
        <div class="net-worth">
          <div class="nw-item" v-if="!filters.category && !filters.region">
            <span class="nw-label">诉求总量</span>
            <span class="nw-value">{{ stats.appeal_total || 0 }}</span>
          </div>
          <div class="nw-item">
            <span class="nw-label">工单总量</span>
            <span class="nw-value">{{ stats.order_total || 0 }}</span>
          </div>
        </div>

        <!-- 分类分布（折叠组） -->
        <div class="account-group">
          <div class="group-head" @click="toggle('cat')">
            <el-icon><component :is="catOpen ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
            <span>分类分布</span>
          </div>
          <div v-show="catOpen" class="group-body">
            <div v-for="c in stats.category_distribution" :key="c.category" class="account-row">
              <span class="acc-name">{{ categoryLabel(c.category) }}</span>
              <span class="acc-val">{{ c.cnt }}</span>
            </div>
            <div v-if="!stats.category_distribution?.length" class="acc-empty">暂无数据</div>
          </div>
        </div>

        <!-- 等级分布（折叠组） -->
        <div class="account-group">
          <div class="group-head" @click="toggle('lv')">
            <el-icon><component :is="lvOpen ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
            <span>工单等级</span>
          </div>
          <div v-show="lvOpen" class="group-body">
            <div v-for="l in stats.level_distribution" :key="l.level" class="account-row">
              <span class="acc-name">{{ levelLabel(l.level) }}</span>
              <span class="acc-val">{{ l.cnt }}</span>
            </div>
            <div v-if="!stats.level_distribution?.length" class="acc-empty">暂无数据</div>
          </div>
        </div>

        <!-- 状态分布（折叠组） -->
        <div class="account-group">
          <div class="group-head" @click="toggle('st')">
            <el-icon><component :is="stOpen ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
            <span>分派进度</span>
          </div>
          <div v-show="stOpen" class="group-body">
            <div class="account-row"><span class="acc-name">待分派</span><span class="acc-val">{{ stats.pending_confirm || 0 }}</span></div>
            <div class="account-row"><span class="acc-name">待更新</span><span class="acc-val">{{ stats.pending_update || 0 }}</span></div>
            <div class="account-row"><span class="acc-name">已分派</span><span class="acc-val">{{ stats.assigned || 0 }}</span></div>
            <div class="account-row"><span class="acc-name">分派完成率</span><span class="acc-val">{{ ((stats.dispatch_rate || 0) * 100).toFixed(1) }}%</span></div>
          </div>
        </div>
      </div>

      <!-- 中列：Spending Plan 环形图 + 流水列表 + Top Categories -->
      <div class="col-center">
        <!-- Spending Plan 环形图 -->
        <div class="panel spending-plan">
          <div class="plan-header">
            <span class="panel-header">工单构成</span>
            <span class="panel-sub">分类占比</span>
          </div>
          <div class="plan-body">
            <!-- 环形图（纯 CSS conic-gradient） -->
            <div class="ring-box">
              <div class="ring" :style="{ background: ringGradient }"></div>
              <div class="ring-center">
                <div class="ring-center-val">{{ stats.order_total || 0 }}</div>
                <div class="ring-center-lbl">工单总量</div>
              </div>
            </div>
            <!-- 右侧：分类进度条列表 -->
            <div class="plan-bars">
              <div v-for="(c, i) in ringSegments" :key="c.category" class="plan-bar-item">
                <div class="bar-head">
                  <span class="bar-name">
                    <span class="bar-dot" :style="{ background: categoryColor(c.category) }"></span>
                    {{ segmentName(c.category) }}
                  </span>
                  <span class="bar-pct">{{ pct(c.cnt) }}%</span>
                </div>
                <div class="bar-track">
                  <div class="bar-fill" :style="{ width: pct(c.cnt) + '%', background: categoryColor(c.category) }"></div>
                </div>
                <div class="bar-sub">共 {{ c.cnt }} 件 · 总量 {{ stats.order_total || 0 }} 件</div>
              </div>
              <div v-if="!ringSegments.length" class="acc-empty">暂无分类数据</div>
            </div>
          </div>
        </div>

        <!-- Spent 流水列表（每日趋势） -->
        <div class="panel spent-panel">
          <div class="spent-head">
            <div class="spent-title">{{ totalPeriod }}</div>
            <div class="spent-sub">{{ filters.period === 'DAY' ? '今日新增工单' : periodText + '逐日新增工单' }}</div>
          </div>
          <div class="spent-list" v-loading="loading">
            <div v-for="(t, i) in stats.trend" :key="i" class="spent-item">
              <div class="spent-info">
                <div class="spent-name">{{ t.d }}</div>
                <div class="spent-sub-lbl">日期</div>
              </div>
              <div class="spent-amt">{{ t.cnt }} 件</div>
            </div>
            <div v-if="!stats.trend?.length && !loading" class="acc-empty">暂无趋势数据</div>
          </div>
        </div>

        <!-- Top Spending Categories 条形图 -->
        <div class="panel top-cats">
          <div class="panel-header">分类排行 <span class="panel-sub">{{ periodText }}工单分类排行</span></div>
          <div class="top-bar-list">
            <div v-for="(c, i) in topCategories" :key="c.category" class="top-bar-row">
              <span class="top-bar-label">{{ categoryLabel(c.category) }}</span>
              <div class="top-bar-track">
                <div class="top-bar-fill" :style="{ width: pct(c.cnt) + '%', background: categoryColor(c.category) }"></div>
              </div>
              <span class="top-bar-cnt">{{ c.cnt }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右列：Bills & Payments（状态卡片 + 概要） -->
      <div class="col-right">
        <div class="panel-header">工单状态分布</div>
        <!-- 状态卡片列表 -->
        <div class="bill-list">
          <div class="bill-item" v-if="stats.pending_confirm > 0">
            <span class="bill-date">待分派</span>
            <div class="bill-body">
              <span class="bill-name">{{ stats.pending_confirm }}</span>
              <span class="bill-sub">件工单待管理端分派</span>
            </div>
            <div class="bill-dot warn"></div>
          </div>
          <div class="bill-item" v-if="stats.pending_update > 0">
            <span class="bill-date">待更新</span>
            <div class="bill-body">
              <span class="bill-name">{{ stats.pending_update }}</span>
              <span class="bill-sub">件工单等待信息更新</span>
            </div>
            <div class="bill-dot info"></div>
          </div>
          <div class="bill-item" v-if="stats.assigned > 0">
            <span class="bill-date">已分派</span>
            <div class="bill-body">
              <span class="bill-name">{{ stats.assigned }}</span>
              <span class="bill-sub">件工单已分派承办部门</span>
            </div>
            <div class="bill-dot ok"></div>
          </div>
          <div v-if="stats.order_total > 0" class="bill-item">
            <span class="bill-date">工单总量</span>
            <div class="bill-body">
              <span class="bill-name">{{ stats.order_total }}</span>
              <span class="bill-sub">全部工单（含已归档）</span>
            </div>
            <div class="bill-dot main"></div>
          </div>
          <div v-if="!stats.order_total && !loading" class="acc-empty">暂无工单数据</div>
        </div>

        <!-- 状态明细（真实状态分布，字典中文 + 状态色点） -->
        <div class="panel-header">状态明细</div>
        <div class="bill-list">
          <div v-for="s in statusList" :key="s.status" class="bill-item">
            <span class="bill-date">{{ s.label }}</span>
            <div class="bill-body">
              <span class="bill-name">{{ s.cnt }}</span>
              <span class="bill-sub">件工单处于该状态</span>
            </div>
            <div class="bill-dot" :class="s.dot"></div>
          </div>
          <div v-if="!statusList.length && !loading" class="acc-empty">暂无状态数据</div>
        </div>

        <!-- 分派完成率进度卡 -->
        <div class="panel dispatch-panel">
          <div class="dispatch-title">分派完成率</div>
          <div class="dispatch-val">{{ ((stats.dispatch_rate || 0) * 100).toFixed(1) }}%</div>
          <div class="dispatch-track">
            <div class="dispatch-fill" :style="{ width: ((stats.dispatch_rate || 0) * 100) + '%' }"></div>
          </div>
          <div class="dispatch-sub">{{ stats.assigned || 0 }} / {{ stats.order_total || 0 }} 件已分派</div>
        </div>

        <!-- AI 概要 -->
        <div class="panel summary-panel" v-if="stats.ai_summary">
          <div class="panel-header">AI 统计概要</div>
          <div class="summary-text">{{ stats.ai_summary }}</div>
        </div>

        <!-- 导出按钮 -->
        <el-button @click="handleExport" :loading="exporting" class="export-btn">
          导出 Excel 报表
        </el-button>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { reactive, computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLayout from '../components/AdminLayout.vue'
import { getStatsSummary, exportStatsReport } from '../api/stats'
import { getPrimaries } from '../api/classify'
import { getCounties } from '../api/order'
import { orderStatusLabel, orderStatusColor } from '../../shared/dict'
import { saveBlobResponse } from '../../shared/request.js'

const filters = reactive({ period: 'MONTH', category: '', region: '' })

// 区县下拉数据（复用工单管理录入弹窗同款公开接口；失败留空不阻断统计）
const counties = ref([])
const loadCounties = async () => {
  try { counties.value = await getCounties() } catch { counties.value = [] }
}

// 周期动态文案：与 filters.period 联动（DAY 后端口径为自然日 = 今日）
const periodText = computed(() =>
  ({ WEEK: '近7天', MONTH: '近30天', DAY: '今日' })[filters.period] || '近30天'
)

const stats = reactive({
  appeal_total: 0, order_total: 0, pending_confirm: 0, pending_update: 0,
  assigned: 0, dispatch_rate: 0,
  category_distribution: [], status_distribution: [], level_distribution: [], trend: [],
  ai_summary: ''
})
const loading = ref(false)
const exporting = ref(false)

// 折叠状态
const catOpen = ref(true)
const lvOpen = ref(true)
const stOpen = ref(true)
const toggle = (key) => {
  if (key === 'cat') catOpen.value = !catOpen.value
  if (key === 'lv') lvOpen.value = !lvOpen.value
  if (key === 'st') stOpen.value = !stOpen.value
}

// 分类 → 颜色映射（现行 8 大一级分类：classify_primary 种子数据；未分类/历史分类兜底灰）
const categoryColor = (c) => ({
  URBAN: '#6ee7b7',       // 城市管理 - 青绿
  TRAFFIC: '#fbbf24',     // 交通管理 - 琥珀
  MARKET: '#f87171',      // 市场监管 - 珊瑚红
  ENVIRONMENT: '#4ade80', // 环境保护 - 草绿
  HOUSING: '#60a5fa',     // 住房保障 - 亮蓝
  LABOR: '#f472b6',       // 劳动保障 - 玫粉
  PUBLIC: '#2dd4bf',      // 公共服务 - 青
  PROPERTY: '#a78bfa',    // 物业 - 紫（历史遗留）
  LIVELIHOOD: '#f59e0b',  // 民生 - 橙（历史遗留）
  HEALTH: '#a78bfa',      // 医疗卫生 - 柔紫
  __UNCLASSIFIED__: '#d4d4d8' // 未分类 - 灰
}[c] || '#d4d4d8')

// 一级分类 code -> 名称（动态来自 classify_primary，8 大一级分类）
const primaries = ref([])
const primaryMap = reactive({})
const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略 */ }
}

// 分类/等级标签映射（后端枚举）
const categoryLabel = (c) => primaryMap[c] || c || '未分类'
const levelLabel = (l) => ({ 一级: '一级', 二级: '二级', 三级: '三级' }[l] || l || '未分级')

// Top 分类（按数量降序）
const topCategories = computed(() =>
  [...(stats.category_distribution || [])].sort((a, b) => b.cnt - a.cnt)
)

// 未分类工单 = 总量 − 已分类合计（分类分布 SQL 排除了 category IS NULL 的工单）
const unclassifiedCnt = computed(() => {
  const sum = (stats.category_distribution || []).reduce((s, c) => s + (c.cnt || 0), 0)
  return Math.max(stats.order_total - sum, 0)
})

// 环形图/分类条数据源：已分类 + 未分类灰段，保证 360° 闭合
const ringSegments = computed(() => {
  const segs = [...(stats.category_distribution || [])]
  if (unclassifiedCnt.value > 0) segs.push({ category: '__UNCLASSIFIED__', cnt: unclassifiedCnt.value })
  return segs.sort((a, b) => b.cnt - a.cnt)
})

// 分段显示名：未分类段特殊处理，其余走动态分类字典
const segmentName = (c) => (c === '__UNCLASSIFIED__' ? '未分类' : categoryLabel(c))

// 百分比工具（分母统一为工单总量，与环形图口径一致）
const pct = (cnt) => {
  const total = stats.order_total || 1
  return Math.round(((cnt || 0) / total) * 100)
}

// 所选周期内趋势行总和（与周期联动，非固定 7 日）
const totalPeriod = computed(() =>
  (stats.trend || []).reduce((s, t) => s + (t.cnt || 0), 0)
)

// 环形图 conic-gradient
const ringGradient = computed(() => {
  const segs = ringSegments.value
  if (!segs.length) return 'conic-gradient(#d4d4d8 0deg 360deg)'
  let angle = 0
  const parts = []
  segs.forEach((s, i) => {
    const share = ((s.cnt || 0) / (stats.order_total || 1)) * 360
    const start = angle
    const end = angle + share
    parts.push(`${categoryColor(s.category)} ${start}deg ${end}deg`)
    angle = end
  })
  return `conic-gradient(${parts.join(', ')})`
})

// 工单状态分布（管理端文案 + 状态色点）：字典只读引用，旧状态 PENDING_CONFIRM 兼容显示
const dotClass = { warning: 'warn', info: 'info', success: 'ok', danger: 'err', primary: 'main' }
const statusList = computed(() =>
  (stats.status_distribution || []).map(s => ({
    ...s,
    label: orderStatusLabel(s.status, 'ADMIN'),
    dot: dotClass[orderStatusColor(s.status, 'ADMIN')] || 'info'
  }))
)

const loadStats = async () => {
  loading.value = true
  try {
    const data = await getStatsSummary({
      period: filters.period,
      category: filters.category || undefined,
      region: filters.region || undefined
    })
    Object.assign(stats, data)
  } catch {
    ElMessage.error('统计数据加载失败，请稍后重试')
  }
  loading.value = false
}

const handleExport = async () => {
  exporting.value = true
  try {
    // 流式下载：后端直接回写 Excel 响应流（零落盘）
    const res = await exportStatsReport({
      period: filters.period,
      category: filters.category || undefined,
      region: filters.region || undefined
    })
    saveBlobResponse(res, `统计报表_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`)
    ElMessage.success('报表已生成，正在下载')
  } catch {
    // 拦截器已提示
  }
  exporting.value = false
}

onMounted(() => {
  loadClassifyDict()
  loadCounties()
  loadStats()
})
</script>

<style scoped>
/* ========== 极简浅色风（与 AdminLayout 统一） ========== */
.stats-filter {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

/* 主网格：Accounts 240px + Center 1fr + Right 260px */
.stats-grid {
  display: grid;
  grid-template-columns: 240px 1fr 260px;
  gap: 16px;
  align-items: start;
}

/* ---- 左列 Accounts ---- */
.col-accounts {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 10px;
  padding: 16px 14px;
}
.panel-header {
  font-size: 14px;
  font-weight: 700;
  color: #18181b;
  margin-bottom: 12px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.panel-sub {
  font-size: 13px;
  font-weight: 400;
  color: #8e8e93;
}
.net-worth {
  padding: 10px 6px 14px;
  border-bottom: 1px solid #ececec;
  margin-bottom: 10px;
  display: flex;
  gap: 12px;
}
.nw-item { flex: 1; min-width: 0; }
.nw-label { font-size: 13px; color: #8e8e93; }
.nw-value {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: #18181b;
  margin-top: 2px;
}

.account-group { margin-bottom: 4px; }
.group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 6px;
  font-size: 13px;
  font-weight: 600;
  color: #27272a;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.12s;
}
.group-head:hover { background: #f4f4f2; }
.group-head .el-icon { color: #a1a1aa; font-size: 13px; }

.group-body { padding: 2px 6px 6px; }
.account-row {
  display: flex;
  justify-content: space-between;
  padding: 5px 0 5px 16px;
  font-size: 13px;
  color: #52525b;
}
.acc-val { font-weight: 600; color: #18181b; font-variant-numeric: tabular-nums; }
.acc-empty { font-size: 13px; color: #a1a1aa; padding: 6px 0 6px 16px; }

/* ---- 中列 ---- */
.col-center { display: flex; flex-direction: column; gap: 16px; }
.panel {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 10px;
  padding: 16px 18px;
}

/* Spending Plan */
.spending-plan .plan-header { margin-bottom: 14px; }
.plan-body { display: flex; gap: 24px; }
.ring-box {
  position: relative;
  width: 150px;
  height: 150px;
  flex-shrink: 0;
}
.ring {
  width: 100%;
  height: 100%;
  border-radius: 50%;
}
.ring-center {
  position: absolute;
  top: 10px; left: 10px; right: 10px; bottom: 10px;
  background: #fff;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.ring-center-val { font-size: 22px; font-weight: 700; color: #18181b; }
.ring-center-lbl { font-size: 12px; color: #8e8e93; margin-top: 2px; }

.plan-bars { flex: 1; display: flex; flex-direction: column; gap: 14px; }
.plan-bar-item { }
.bar-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: 12.5px;
}
.bar-name { display: flex; align-items: center; gap: 6px; color: #27272a; }
.bar-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.bar-pct { color: #52525b; font-weight: 600; }
.bar-track {
  height: 6px;
  background: #f1f1ef;
  border-radius: 3px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}
.bar-sub { font-size: 12px; color: #a1a1aa; margin-top: 2px; }

/* Spent 流水列表 */
.spent-panel { padding: 14px 18px; }
.spent-head { margin-bottom: 10px; }
.spent-title { font-size: 20px; font-weight: 700; color: #18181b; }
.spent-sub { font-size: 13px; color: #8e8e93; margin-top: -2px; }
.spent-list { display: flex; flex-direction: column; max-height: 220px; overflow-y: auto; }
.spent-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 2px;
  border-bottom: 1px solid #f4f4f2;
}
.spent-item:last-child { border-bottom: none; }
.spent-info .spent-name { font-size: 13px; font-weight: 600; color: #27272a; }
.spent-sub-lbl { font-size: 12px; color: #a1a1aa; }
.spent-amt { font-size: 14px; font-weight: 700; color: #18181b; }

/* Top Categories */
.top-cats .top-bar-list { display: flex; flex-direction: column; gap: 8px; }
.top-bar-row {
  display: grid;
  grid-template-columns: 70px 1fr 40px;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}
.top-bar-label { color: #52525b; }
.top-bar-track {
  height: 8px;
  background: #f1f1ef;
  border-radius: 4px;
  overflow: hidden;
}
.top-bar-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
.top-bar-cnt { font-weight: 700; color: #18181b; text-align: right; }

/* ---- 右列 ---- */
.col-right { display: flex; flex-direction: column; gap: 16px; }
.bill-list {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 10px;
  padding: 14px;
}
.bill-item {
  display: grid;
  grid-template-columns: 70px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #f4f4f2;
}
.bill-item:last-child { border-bottom: none; }
.bill-date { font-size: 12px; color: #a1a1aa; text-transform: uppercase; letter-spacing: 0.5px; }
.bill-body { display: flex; flex-direction: column; }
.bill-name { font-size: 14px; font-weight: 700; color: #18181b; }
.bill-sub { font-size: 12px; color: #8e8e93; }
.bill-dot { width: 10px; height: 10px; border-radius: 50%; }
.bill-dot.warn { background: #f59e0b; }
.bill-dot.info { background: #8e8e93; }
.bill-dot.ok { background: #10b981; }
.bill-dot.err { background: #ef4444; }
.bill-dot.main { background: #18181b; }

.dispatch-panel { text-align: center; padding: 18px 16px; }
.dispatch-title { font-size: 13px; color: #8e8e93; margin-bottom: 4px; }
.dispatch-val { font-size: 28px; font-weight: 700; color: #18181b; }
.dispatch-track {
  height: 6px;
  background: #f1f1ef;
  border-radius: 3px;
  overflow: hidden;
  margin: 10px 0 4px;
}
.dispatch-fill {
  height: 100%;
  background: #18181b;
  border-radius: 3px;
  transition: width 0.5s ease;
}
.dispatch-sub { font-size: 12px; color: #a1a1aa; }

.summary-panel .summary-text {
  font-size: 13px;
  color: #52525b;
  line-height: 1.6;
}

.export-btn {
  width: 100%;
  background: #18181b;
  border-color: #18181b;
  color: #fff;
  border-radius: 8px;
}
.export-btn:hover {
  background: #27272a;
  border-color: #27272a;
}

/* ---- 响应式 ---- */
@media (max-width: 1100px) {
  .stats-grid { grid-template-columns: 200px 1fr; }
  .col-right { grid-column: 1 / -1; flex-direction: row; flex-wrap: wrap; }
  .col-right > * { flex: 1; min-width: 200px; }
}
@media (max-width: 768px) {
  .stats-grid { grid-template-columns: 1fr; }
  .plan-body { flex-direction: column; align-items: center; }
}
</style>
